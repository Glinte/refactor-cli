package io.github.glinte.refactor.operations

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.glinte.refactor.protocol.RefactorException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RefactorOperationRouterTest : BasePlatformTestCase() {
    fun testStatusReportsOpenProject() {
        val result = RefactorOperationRouter()
            .route("status", JsonObject(emptyMap()))
            .jsonObject
        val projects = result.getValue("projects").jsonArray

        assertEquals(1, result.getValue("protocolVersion").jsonPrimitive.int)
        assertNotNull(result["watcherOperational"])
        assertTrue(
            result.getValue("languagePlugins").jsonObject.getValue("java").jsonPrimitive.boolean,
        )
        assertEquals(
            "K2",
            result.getValue("languagePlugins").jsonObject
                .getValue("kotlinMode").jsonPrimitive.content,
        )
        assertTrue(projects.any { it.jsonObject.getValue("name").jsonPrimitive.content == project.name })
    }

    fun testResolvesAndGuardsJavaSymbol() {
        addJavaFixture()
        val result = route(
            "resolve",
            request {
                put("selector", buildJsonObject {
                    put("symbol", "com.example.User")
                    put("expect", "User:CLASS")
                })
            },
        ).jsonObject

        assertEquals("com.example.User", result.getValue("qualifiedName").jsonPrimitive.content)
        assertEquals("CLASS", result.getValue("kind").jsonPrimitive.content)
    }

    fun testGuardMismatchIsStructured() {
        addJavaFixture()
        val error = expectRefactor {
            route(
                "resolve",
                request {
                    put("selector", buildJsonObject {
                        put("symbol", "com.example.User")
                        put("expect", "Account:CLASS")
                    })
                },
            )
        }

        assertEquals("TARGET_MISMATCH", error.symbolicCode)
        assertEquals("User", error.details.getValue("actualName").jsonPrimitive.content)
    }

    fun testPositionAndUnsupportedSymbolErrorsAreStructured() {
        val missingFile = expectRefactor {
            route(
                "resolve",
                request {
                    put("selector", buildJsonObject {
                        put("file", "missing.py")
                        put("line", 1)
                        put("col", 1)
                    })
                },
            )
        }
        assertEquals("FILE_NOT_FOUND", missingFile.symbolicCode)

        myFixture.addFileToProject("example/short.py", "value = 1")
        val badPosition = expectRefactor {
            route(
                "resolve",
                request {
                    put("selector", buildJsonObject {
                        put("file", "example/short.py")
                        put("line", 1)
                        put("col", 0)
                    })
                },
            )
        }
        assertEquals("POSITION_OUT_OF_RANGE", badPosition.symbolicCode)

        myFixture.addFileToProject("resources/plain.xml", "<root/>")
        val unsupported = expectRefactor {
            route(
                "resolve",
                request {
                    put("selector", buildJsonObject {
                        put("file", "resources/plain.xml")
                        put("line", 1)
                        put("col", 2)
                    })
                },
            )
        }
        assertEquals("UNSUPPORTED_SYMBOL", unsupported.symbolicCode)
    }

    fun testPositionSelectorPrefersExactProjectPathAndRejectsAmbiguousFallback() {
        val exact = myFixture.addFileToProject(
            "src/model/User.java",
            "package model; public class User {}",
        )
        myFixture.addFileToProject(
            "module/src/model/User.java",
            "package module.model; public class User {}",
        )
        val offset = exact.text.indexOf("User")
        val document = exact.viewProvider.document!!
        val line = document.getLineNumber(offset)
        val resolved = route(
            "resolve",
            request {
                put("selector", buildJsonObject {
                    put("file", "src/model/User.java")
                    put("line", line + 1)
                    put("col", offset - document.getLineStartOffset(line) + 1)
                    put("expect", "User:CLASS")
                })
            },
        ).jsonObject

        assertEquals("model.User", resolved.getValue("qualifiedName").jsonPrimitive.content)

        val ambiguous = expectRefactor {
            route(
                "resolve",
                request {
                    put("selector", buildJsonObject {
                        put("file", "model/User.java")
                        put("line", 1)
                        put("col", 29)
                    })
                },
            )
        }
        assertEquals("AMBIGUOUS_TARGET", ambiguous.symbolicCode)
        assertEquals(2, ambiguous.details.getValue("candidates").jsonArray.size)
    }

    fun testTouchedRefreshSeesImmediateExternalRewrite() {
        withPhysicalJavaFile(
            "com/example/Before.java",
            "package com.example; public class Before {}",
        ) { file ->
            route(
                "resolve",
                request {
                    put("selector", buildJsonObject { put("symbol", "com.example.Before") })
                },
            )
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(file.path),
                "package com.example; public class After {}",
            )

            val result = route(
                "resolve",
                request {
                    put("touched", buildJsonArray { add(file.path) })
                    put("selector", buildJsonObject { put("symbol", "com.example.After") })
                },
            ).jsonObject

            assertEquals("After", result.getValue("name").jsonPrimitive.content)
        }
    }

    fun testIncorrectTouchedHintCannotHideExternalRewrite() {
        withPhysicalJavaFile(
            "com/example/BeforeWrongHint.java",
            "package com.example; public class BeforeWrongHint {}",
        ) { file ->
            route(
                "resolve",
                request {
                    put(
                        "selector",
                        buildJsonObject { put("symbol", "com.example.BeforeWrongHint") },
                    )
                },
            )
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(file.path),
                "package com.example; public class AfterWrongHint {}",
            )
            val wrongHint = java.nio.file.Path.of(file.path).parent.resolve("unchanged.txt")

            val result = route(
                "resolve",
                request {
                    put("touched", buildJsonArray { add(wrongHint.toString()) })
                    put(
                        "selector",
                        buildJsonObject { put("symbol", "com.example.AfterWrongHint") },
                    )
                },
            ).jsonObject

            assertEquals("AfterWrongHint", result.getValue("name").jsonPrimitive.content)
        }
    }

    fun testFullRefreshSeesImmediateExternalDeletion() {
        withPhysicalJavaFile(
            "com/example/Deleted.java",
            "package com.example; public class Deleted {}",
        ) { file ->
            route(
                "resolve",
                request {
                    put("selector", buildJsonObject { put("symbol", "com.example.Deleted") })
                },
            )
            java.nio.file.Files.delete(java.nio.file.Path.of(file.path))

            val error = expectRefactor {
                route(
                    "resolve",
                    request {
                        put("selector", buildJsonObject { put("symbol", "com.example.Deleted") })
                    },
                )
            }

            assertEquals("SYMBOL_NOT_FOUND", error.symbolicCode)
        }
    }

    fun testFullRefreshSeesImmediateExternalCreation() {
        withPhysicalJavaFile(
            "com/example/Anchor.java",
            "package com.example; public class Anchor {}",
        ) { anchor ->
            route(
                "resolve",
                request {
                    put("selector", buildJsonObject { put("symbol", "com.example.Anchor") })
                },
            )
            val created = java.nio.file.Path.of(anchor.path).parent.resolve("Created.java")
            java.nio.file.Files.writeString(
                created,
                "package com.example; public class Created {}",
            )

            val result = route(
                "resolve",
                request {
                    put("selector", buildJsonObject { put("symbol", "com.example.Created") })
                },
            ).jsonObject

            assertEquals("Created", result.getValue("name").jsonPrimitive.content)
        }
    }

    fun testUnsavedAndExternalWritesProduceStructuredConflict() {
        withPhysicalJavaFile(
            "com/example/Concurrent.java",
            "package com.example; public class Concurrent { int value; }",
        ) { file ->
            route(
                "resolve",
                request {
                    put("selector", buildJsonObject { put("symbol", "com.example.Concurrent") })
                },
            )
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.insertString(document.textLength, "\n// unsaved IDE edit")
                }
            }
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(file.path),
                "package com.example; public class Concurrent { long externallyChanged; }",
            )

            val error = expectRefactor {
                route(
                    "resolve",
                    request {
                        put("selector", buildJsonObject { put("symbol", "com.example.Concurrent") })
                    },
                )
            }

            assertEquals("EXTERNAL_CHANGE_CONFLICT", error.symbolicCode)
            assertEquals(4, error.exitCode)
            FileDocumentManager.getInstance().reloadFromDisk(document, project)
        }
    }

    fun testSyncTimeoutIsStructured() {
        val token = com.intellij.testFramework.DumbModeTestUtils
            .startEternalDumbModeTask(project)
        try {
            val result = PlatformTestUtil.waitForFuture(
                ApplicationManager.getApplication().executeOnPooledThread<Any> {
                    try {
                        WorkspaceService(smartModeTimeoutMs = 25).sync(project, request {})
                    } catch (error: Throwable) {
                        error
                    }
                },
                30_000,
            )

            assertTrue(result is RefactorException)
            val error = result as RefactorException
            assertEquals("SYNC_TIMEOUT", error.symbolicCode)
            assertEquals(4, error.exitCode)
            assertEquals(25, error.details.getValue("timeoutMs").jsonPrimitive.int)
            assertTrue(error.details.getValue("elapsedMs").jsonPrimitive.int >= 25)
        } finally {
            com.intellij.testFramework.DumbModeTestUtils
                .endEternalDumbModeTaskAndWaitForSmartMode(project, token)
        }
    }

    fun testJvmDescriptorSelectsOverload() {
        addJavaFixture()
        val result = route(
            "resolve",
            request {
                put("selector", buildJsonObject {
                    put("symbol", "com.example.User#convert(I)I")
                    put("expect", "convert:METHOD")
                })
            },
        ).jsonObject

        assertEquals("METHOD", result.getValue("kind").jsonPrimitive.content)
        assertEquals(5, result.getValue("line").jsonPrimitive.int)
    }

    fun testOverloadWithoutDescriptorIsAmbiguous() {
        addJavaFixture()
        val error = expectRefactor {
            route(
                "resolve",
                request {
                    put("selector", buildJsonObject { put("symbol", "com.example.User#convert") })
                },
            )
        }

        assertEquals("AMBIGUOUS_TARGET", error.symbolicCode)
        assertEquals(2, error.details.getValue("candidates").jsonArray.size)
    }

    fun testFindsSemanticJavaUsages() {
        addJavaFixture()
        val result = route(
            "usages",
            request {
                put("max", 10)
                put("selector", buildJsonObject { put("symbol", "com.example.User#name") })
            },
        ).jsonObject

        assertTrue(result.getValue("totalUsages").jsonPrimitive.int >= 1)
        assertFalse(result.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(result.getValue("usages").jsonArray.isNotEmpty())
    }

    fun testFindsSemanticKotlinAndPythonUsages() {
        myFixture.addFileToProject(
            "usage/KModel.kt",
            "package usage\nclass KModel",
        )
        myFixture.addFileToProject(
            "usage/UseKModel.kt",
            "package usage\nfun load(value: KModel): KModel = value",
        )
        val kotlinUsages = route(
            "usages",
            request {
                put("selector", buildJsonObject {
                    put("symbol", "usage.KModel")
                    put("expect", "KModel:CLASS")
                })
                put("max", 200)
            },
        ).jsonObject

        assertEquals(2, kotlinUsages.getValue("totalUsages").jsonPrimitive.int)
        assertTrue(kotlinUsages.getValue("warnings").jsonArray.isEmpty())
        assertEquals(
            setOf("usage/UseKModel.kt"),
            kotlinUsages.getValue("usages").jsonArray
                .map { it.jsonObject.getValue("path").jsonPrimitive.content }
                .toSet(),
        )

        val model = myFixture.addFileToProject(
            "pyusage/model.py",
            "class User:\n    pass",
        )
        myFixture.addFileToProject(
            "pyusage/consumer.py",
            "from .model import User\n\ndef load(value: User) -> User:\n    return value",
        )
        val document = model.viewProvider.document!!
        val offset = model.text.indexOf("User")
        val line = document.getLineNumber(offset)
        val pythonUsages = route(
            "usages",
            request {
                put("selector", buildJsonObject {
                    put("file", "pyusage/model.py")
                    put("line", line + 1)
                    put("col", offset - document.getLineStartOffset(line) + 1)
                    put("expect", "User:CLASS")
                })
                put("max", 200)
            },
        ).jsonObject

        assertTrue(pythonUsages.getValue("totalUsages").jsonPrimitive.int >= 3)
        assertTrue(pythonUsages.getValue("warnings").jsonArray.isNotEmpty())
        assertEquals(
            setOf("pyusage/consumer.py"),
            pythonUsages.getValue("usages").jsonArray
                .map { it.jsonObject.getValue("path").jsonPrimitive.content }
                .toSet(),
        )
    }

    fun testSpringXmlReferenceRequiresExplicitNonSourceApproval() {
        val user = myFixture.addFileToProject(
            "com/example/ManagedUser.java",
            "package com.example; public class ManagedUser {}",
        )
        val xml = myFixture.addFileToProject(
            "resources/applicationContext.xml",
            """
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:schemaLocation="http://www.springframework.org/schema/beans
                                           https://www.springframework.org/schema/beans/spring-beans.xsd">
                    <bean id="managedUser" class="com.example.ManagedUser"/>
                </beans>
            """.trimIndent(),
        )
        val selector = buildJsonObject { put("symbol", "com.example.ManagedUser") }

        val usages = route(
            "usages",
            request {
                put("max", 10)
                put("selector", selector)
            },
        ).jsonObject
        assertTrue(
            usages.getValue("summary").jsonObject.getValue("nonSource").jsonPrimitive.int > 0,
        )

        val review = expectRefactor {
            route(
                "rename",
                request {
                    put("selector", selector)
                    put("to", "Account")
                    put("dryRun", false)
                    put("forceNonSource", false)
                    put("diff", "none")
                },
            )
        }
        assertEquals("NEEDS_REVIEW", review.symbolicCode)
        assertEquals("ManagedUser.java", user.virtualFile.name)
        assertTrue(xml.text.contains("com.example.ManagedUser"))

        val result = route(
            "rename",
            request {
                put("selector", selector)
                put("to", "Account")
                put("dryRun", false)
                put("forceNonSource", true)
                put("diff", "none")
            },
        ).jsonObject
        assertEquals("APPLIED", result.getValue("status").jsonPrimitive.content)
        assertEquals("Account.java", user.virtualFile.name)
        assertTrue(xml.text.contains("com.example.Account"))
    }

    fun testPersistenceXmlClassReferenceIsRenamedSemantically() {
        val entity = myFixture.addFileToProject(
            "com/example/PersistentUser.java",
            "package com.example; public class PersistentUser {}",
        )
        val xml = myFixture.addFileToProject(
            "resources/META-INF/persistence.xml",
            """
                <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.0">
                    <persistence-unit name="example">
                        <class>com.example.PersistentUser</class>
                    </persistence-unit>
                </persistence>
            """.trimIndent(),
        )
        val result = route(
            "rename",
            request {
                put("selector", buildJsonObject { put("symbol", "com.example.PersistentUser") })
                put("to", "PersistentAccount")
                put("dryRun", false)
                put("forceNonSource", true)
                put("diff", "none")
            },
        ).jsonObject

        assertEquals("APPLIED", result.getValue("status").jsonPrimitive.content)
        assertEquals("PersistentAccount.java", entity.virtualFile.name)
        assertTrue(xml.text.contains("com.example.PersistentAccount"))
        assertTrue(
            result.getValue("changedFiles").jsonArray.any {
                it.jsonObject.getValue("kind").jsonPrimitive.content == "NON_SOURCE"
            },
        )
    }

    fun testDryRunReportsWithoutChangingFiles() {
        val user = addJavaFixture()
        val error = expectRefactor {
            route(
                "rename",
                renameRequest(dryRun = true),
            )
        }

        assertEquals(2, error.exitCode)
        assertEquals("NEEDS_REVIEW", error.symbolicCode)
        assertEquals("NEEDS_REVIEW", error.details.getValue("status").jsonPrimitive.content)
        assertTrue(user.text.contains("name"))
        assertFalse(user.text.contains("displayName"))
    }

    fun testConflictReportsWithoutApplying() {
        val file = myFixture.addFileToProject(
            "com/example/Conflict.java",
            """
                package com.example;
                public class Conflict {
                    public String first;
                    public String second;
                }
            """.trimIndent(),
        )
        val error = expectRefactor {
            route(
                "rename",
                request {
                    put("selector", buildJsonObject { put("symbol", "com.example.Conflict#first") })
                    put("to", "second")
                    put("dryRun", false)
                    put("forceNonSource", false)
                    put("diff", "none")
                },
            )
        }

        assertEquals("NEEDS_REVIEW", error.symbolicCode)
        assertTrue(error.details.getValue("conflicts").jsonArray.isNotEmpty())
        assertTrue(file.text.contains("String first"))
    }

    fun testJavaMethodSignatureConflictReportsWithoutApplying() {
        val file = myFixture.addFileToProject(
            "com/example/Methods.java",
            """
                package com.example;
                public class Methods {
                    void first(int value) {}
                    void second(int value) {}
                }
            """.trimIndent(),
        )
        val error = expectRefactor {
            route(
                "rename",
                request {
                    put("selector", buildJsonObject { put("symbol", "com.example.Methods#first") })
                    put("to", "second")
                    put("dryRun", false)
                    put("forceNonSource", false)
                    put("diff", "none")
                },
            )
        }

        assertEquals("NEEDS_REVIEW", error.symbolicCode)
        assertTrue(error.details.getValue("conflicts").jsonArray.isNotEmpty())
        assertTrue(file.text.contains("void first"))
    }

    fun testKotlinParameterConflictReportsWithoutApplying() {
        val file = myFixture.addFileToProject(
            "conflicts/Conflict.kt",
            "package conflicts\nfun combine(first: Int, second: Int) = first + second",
        )
        val error = expectRefactor {
            renameAt(file, "conflicts/Conflict.kt", "first", "PARAMETER", "second")
        }

        assertEquals("NEEDS_REVIEW", error.symbolicCode)
        assertTrue(error.details.getValue("conflicts").jsonArray.isNotEmpty())
        assertTrue(file.text.contains("first: Int"))
    }

    fun testPythonParameterConflictReportsWithoutApplying() {
        val file = myFixture.addFileToProject(
            "conflicts/conflict.py",
            "def combine(first, second):\n    return first + second",
        )
        val document = file.viewProvider.document!!
        val offset = document.text.indexOf("first")
        val line = document.getLineNumber(offset)
        val error = expectRefactor {
            route(
                "rename",
                request {
                    put("selector", buildJsonObject {
                        put("file", "conflicts/conflict.py")
                        put("line", line + 1)
                        put("col", offset - document.getLineStartOffset(line) + 1)
                        put("expect", "first:PARAMETER")
                    })
                    put("to", "second")
                    put("dryRun", false)
                    put("forceNonSource", false)
                    put("diff", "none")
                },
            )
        }

        assertEquals("NEEDS_REVIEW", error.symbolicCode)
        assertTrue(error.details.getValue("conflicts").jsonArray.isNotEmpty())
        assertTrue(file.text.contains("first"))
    }

    fun testRenamesJavaFieldAndReferences() {
        val user = addJavaFixture()
        val result = route("rename", renameRequest(dryRun = false)).jsonObject

        assertEquals("APPLIED", result.getValue("status").jsonPrimitive.content)
        assertTrue(user.text.contains("displayName"))
        assertFalse(user.text.contains(" name"))
        assertEquals(2, result.getValue("changedFiles").jsonArray.size)
    }

    fun testInlineDiffIsReturnedOnRequest() {
        addJavaFixture()
        val result = route(
            "rename",
            request {
                put("selector", buildJsonObject { put("symbol", "com.example.User#name") })
                put("to", "displayName")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "inline")
            },
        ).jsonObject
        val diff = result.getValue("diff").jsonObject

        assertEquals("inline", diff.getValue("mode").jsonPrimitive.content)
        assertFalse(diff.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(diff.getValue("content").jsonPrimitive.content.contains("diff --git"))
        assertTrue(diff.getValue("content").jsonPrimitive.content.contains("+    public String displayName;"))
    }

    fun testFileDiffWritesFullPatch() {
        addJavaFixture()
        val result = route(
            "rename",
            request {
                put("selector", buildJsonObject { put("symbol", "com.example.User#name") })
                put("to", "displayName")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "file")
            },
        ).jsonObject
        val path = java.nio.file.Path.of(
            result.getValue("diff").jsonObject.getValue("path").jsonPrimitive.content,
        )

        try {
            assertTrue(java.nio.file.Files.exists(path))
            assertTrue(java.nio.file.Files.readString(path).contains("diff --git"))
        } finally {
            java.nio.file.Files.deleteIfExists(path)
        }
    }

    fun testRegionsAreGroupedCappedAndPayloadStaysCompact() {
        val methods = (1..200).joinToString("\n\n") {
            "    int read$it() { return value; }"
        }
        myFixture.addFileToProject(
            "com/example/ManyUsages.java",
            """
                package com.example;
                public class ManyUsages {
                    int value;

                $methods
                }
            """.trimIndent(),
        )
        val result = route(
            "rename",
            request {
                put("selector", buildJsonObject {
                    put("symbol", "com.example.ManyUsages#value")
                })
                put("to", "count")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "none")
            },
        ).jsonObject
        val changedFile = result.getValue("changedFiles").jsonArray.single().jsonObject

        assertEquals(20, changedFile.getValue("regions").jsonArray.size)
        assertTrue(changedFile.getValue("regionsTruncated").jsonPrimitive.boolean)
        assertTrue(kotlinx.serialization.json.Json.encodeToString(result).length < 6_000)
    }

    fun testInlineDiffIsCappedAt64KiB() {
        val padding = (1..2_500).joinToString("\n") {
            "    // padding line $it keeps the full-file patch intentionally large"
        }
        myFixture.addFileToProject(
            "com/example/Large.java",
            """
                package com.example;
                public class Large {
                    int value;
                $padding
                    int read() { return value; }
                }
            """.trimIndent(),
        )
        val result = route(
            "rename",
            request {
                put("selector", buildJsonObject { put("symbol", "com.example.Large#value") })
                put("to", "count")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "inline")
            },
        ).jsonObject
        val diff = result.getValue("diff").jsonObject
        val content = diff.getValue("content").jsonPrimitive.content

        assertTrue(diff.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(content.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        assertTrue(diff.getValue("totalBytes").jsonPrimitive.int > 64 * 1024)
    }

    fun testRenamesJavaClassFileAndReferences() {
        val user = addJavaFixture()
        val consumer = com.intellij.psi.PsiManager.getInstance(project).findFile(
            myFixture.findFileInTempDir("com/example/Consumer.java"),
        )!!
        val result = route(
            "rename",
            request {
                put("selector", buildJsonObject {
                    put("symbol", "com.example.User")
                    put("expect", "User:CLASS")
                })
                put("to", "Account")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "inline")
            },
        ).jsonObject

        assertEquals("Account.java", user.virtualFile.name)
        assertTrue(consumer.text.contains("Account"))
        assertEquals(1, result.getValue("renamedPaths").jsonArray.size)
        val patch = result.getValue("diff").jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(patch, patch.contains("rename from com/example/User.java"))
        assertTrue(patch, patch.contains("rename to com/example/Account.java"))
    }

    fun testQualifiedJavaSelectorDoesNotRenameSameSimpleNameInAnotherPackage() {
        val first = myFixture.addFileToProject(
            "first/User.java",
            "package first; public class User {}",
        )
        val second = myFixture.addFileToProject(
            "second/User.java",
            "package second; public class User {}",
        )
        val consumer = myFixture.addFileToProject(
            "consumer/Use.java",
            "package consumer; class Use { first.User first; second.User second; }",
        )

        route(
            "rename",
            request {
                put("selector", buildJsonObject { put("symbol", "first.User") })
                put("to", "Account")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "none")
            },
        )

        assertEquals("Account.java", first.virtualFile.name)
        assertEquals("User.java", second.virtualFile.name)
        assertTrue(consumer.text, consumer.text.contains("import first.Account"))
        assertTrue(consumer.text, consumer.text.contains("Account first"))
        assertTrue(consumer.text, consumer.text.contains("second.User second"))
    }

    fun testCaseOnlyJavaClassRenameChangesFileAndReferences() {
        val user = addJavaFixture()
        val consumer = com.intellij.psi.PsiManager.getInstance(project).findFile(
            myFixture.findFileInTempDir("com/example/Consumer.java"),
        )!!
        val result = route(
            "rename",
            request {
                put("selector", buildJsonObject {
                    put("symbol", "com.example.User")
                    put("expect", "User:CLASS")
                })
                put("to", "user")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "none")
            },
        ).jsonObject

        assertEquals("user.java", user.virtualFile.name)
        assertTrue(consumer.text.contains("user user"))
        assertEquals(
            "com/example/user.java",
            result.getValue("renamedPaths").jsonArray.single()
                .jsonObject.getValue("to").jsonPrimitive.content,
        )
    }

    fun testUnsavedAffectedDocumentBlocksRename() {
        val user = addJavaFixture()
        val document = FileDocumentManager.getInstance().getDocument(user.virtualFile)!!
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(document.textLength, "\n// unsaved human edit")
            }
        }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))

        val error = expectRefactor {
            route("rename", renameRequest(dryRun = false))
        }

        assertEquals("DIRTY_AFFECTED_DOCUMENT", error.symbolicCode)
        assertTrue(user.text.contains("name"))
        assertFalse(user.text.contains("displayName"))
    }

    fun testInvalidNameAndReadOnlyFileBlockRename() {
        val user = addJavaFixture()
        val invalid = expectRefactor {
            route(
                "rename",
                request {
                    put("selector", buildJsonObject { put("symbol", "com.example.User#name") })
                    put("to", "not valid")
                    put("dryRun", false)
                    put("forceNonSource", false)
                    put("diff", "none")
                },
            )
        }
        assertEquals("INVALID_NAME", invalid.symbolicCode)

        com.intellij.openapi.application.WriteAction.run<RuntimeException> {
            user.virtualFile.isWritable = false
        }
        try {
            val readOnly = expectRefactor {
                route("rename", renameRequest(dryRun = false))
            }
            assertEquals("READ_ONLY_FILE", readOnly.symbolicCode)
            assertEquals(4, readOnly.exitCode)
        } finally {
            com.intellij.openapi.application.WriteAction.run<RuntimeException> {
                user.virtualFile.isWritable = true
            }
        }
    }

    fun testProcessorFailureRollsBackAllCapturedFiles() {
        val user = addJavaFixture()
        val consumer = com.intellij.psi.PsiManager.getInstance(project).findFile(
            myFixture.findFileInTempDir("com/example/Consumer.java"),
        )!!
        val resolver = SymbolResolver()
        val service = RenameService(
            resolver,
            UsageService(resolver),
        ) { targetProject, element, newName ->
            RenameProcessor(targetProject, element, newName, false, false).run()
            error("injected failure after mutation")
        }
        val params = renameRequest(dryRun = false)
        val result = PlatformTestUtil.waitForFuture(
            ApplicationManager.getApplication().executeOnPooledThread<Any> {
                try {
                    WorkspaceService().sync(project, params)
                    val symbol = ReadAction.compute<ResolvedSymbol, RuntimeException> {
                        resolver.resolve(project, params)
                    }
                    service.rename(project, params, symbol, 0)
                } catch (error: Throwable) {
                    error
                }
            },
            30_000,
        )

        assertTrue(result is RefactorException)
        val error = result as RefactorException
        assertEquals("REFACTORING_FAILED", error.symbolicCode)
        assertTrue(error.details.getValue("rolledBack").jsonPrimitive.boolean)
        assertTrue(user.text.contains("String name"))
        assertTrue(consumer.text.contains("user.name"))
        assertFalse(user.text.contains("displayName"))
        assertFalse(consumer.text.contains("displayName"))
    }

    fun testRollbackFailureReportsObservedFilesAndNotifies() {
        val user = addJavaFixture()
        val resolver = SymbolResolver()
        val service = RenameService(
            resolver,
            UsageService(resolver),
        ) { targetProject, element, newName ->
            RenameProcessor(targetProject, element, newName, false, false).run()
            WriteCommandAction.runWriteCommandAction(targetProject) {
                user.virtualFile.parent.delete(this)
            }
            error("injected failure after deleting the captured directory")
        }
        val params = renameRequest(dryRun = false)
        val result = PlatformTestUtil.waitForFuture(
            ApplicationManager.getApplication().executeOnPooledThread<Any> {
                try {
                    WorkspaceService().sync(project, params)
                    val symbol = ReadAction.compute<ResolvedSymbol, RuntimeException> {
                        resolver.resolve(project, params)
                    }
                    service.rename(project, params, symbol, 0)
                } catch (error: Throwable) {
                    error
                }
            },
            30_000,
        )

        assertTrue(result is RefactorException)
        val error = result as RefactorException
        assertEquals("ROLLBACK_FAILED", error.symbolicCode)
        assertTrue(error.details.getValue("rollbackErrors").jsonArray.isNotEmpty())
        assertEquals(2, error.details.getValue("observedFiles").jsonArray.size)
        val notifications = com.intellij.notification.NotificationsManager
            .getNotificationsManager()
            .getNotificationsOfType(
                com.intellij.notification.Notification::class.java,
                project,
            )
        assertTrue(
            notifications.any {
                it.title == "refactor-cli rollback failed" &&
                    it.content.contains("Local History label")
            },
        )
    }

    fun testUnpredictedProcessorEditIsReported() {
        addJavaFixture()
        val unrelated = myFixture.addFileToProject(
            "com/example/Unrelated.java",
            "package com.example; class Unrelated {}",
        )
        val resolver = SymbolResolver()
        val service = RenameService(
            resolver,
            UsageService(resolver),
        ) { targetProject, element, newName ->
            RenameProcessor(targetProject, element, newName, false, false).run()
            WriteCommandAction.runWriteCommandAction(targetProject) {
                unrelated.viewProvider.document!!.insertString(
                    unrelated.viewProvider.document!!.textLength,
                    "\n// processor side effect",
                )
            }
        }
        val params = renameRequest(dryRun = false)
        val result = PlatformTestUtil.waitForFuture(
            ApplicationManager.getApplication().executeOnPooledThread<JsonObject> {
                WorkspaceService().sync(project, params)
                val symbol = ReadAction.compute<ResolvedSymbol, RuntimeException> {
                    resolver.resolve(project, params)
                }
                service.rename(project, params, symbol, 0)
            },
            30_000,
        )
        val unexpected = result.getValue("changedFiles").jsonArray
            .map { it.jsonObject }
            .single {
                it.getValue("path").jsonPrimitive.content == "com/example/Unrelated.java"
            }

        assertFalse(unexpected.getValue("expected").jsonPrimitive.boolean)
        assertTrue(unrelated.text.contains("processor side effect"))
    }

    fun testUnpredictedCreatedAndDeletedFilesAreReported() {
        addJavaFixture()
        val removed = myFixture.addFileToProject(
            "com/example/Removed.java",
            "package com.example; class Removed {}",
        )
        val resolver = SymbolResolver()
        val service = RenameService(
            resolver,
            UsageService(resolver),
        ) { targetProject, element, newName ->
            RenameProcessor(targetProject, element, newName, false, false).run()
            WriteCommandAction.runWriteCommandAction(targetProject) {
                val parent = removed.virtualFile.parent
                parent.createChildData(this, "Generated.java").setBinaryContent(
                    "package com.example; class Generated {}".toByteArray(),
                )
                removed.virtualFile.delete(this)
            }
        }
        val params = renameRequest(dryRun = false)
        val result = PlatformTestUtil.waitForFuture(
            ApplicationManager.getApplication().executeOnPooledThread<JsonObject> {
                WorkspaceService().sync(project, params)
                val symbol = ReadAction.compute<ResolvedSymbol, RuntimeException> {
                    resolver.resolve(project, params)
                }
                service.rename(project, params, symbol, 0)
            },
            30_000,
        )
        val changes = result.getValue("changedFiles").jsonArray.map { it.jsonObject }
        val created = changes.singleOrNull {
            it.getValue("path").jsonPrimitive.content == "com/example/Generated.java"
        } ?: throw AssertionError("created file missing from $changes")
        val deleted = changes.singleOrNull {
            it.getValue("path").jsonPrimitive.content == "com/example/Removed.java"
        } ?: throw AssertionError("deleted file missing from $changes")

        assertTrue(created.getValue("created").jsonPrimitive.boolean)
        assertFalse(created.getValue("expected").jsonPrimitive.boolean)
        assertTrue(deleted.getValue("deleted").jsonPrimitive.boolean)
        assertFalse(deleted.getValue("expected").jsonPrimitive.boolean)
    }

    fun testRenameFromOverrideUsesHierarchyRoot() {
        val base = myFixture.addFileToProject(
            "com/example/Base.java",
            """
                package com.example;
                public class Base {
                    public String greet() { return "base"; }
                }
            """.trimIndent(),
        )
        val child = myFixture.addFileToProject(
            "com/example/Child.java",
            """
                package com.example;
                public class Child extends Base {
                    @Override public String greet() { return "child"; }
                }
            """.trimIndent(),
        )
        val consumer = myFixture.addFileToProject(
            "com/example/UseChild.java",
            "package com.example; class UseChild { String call(Child value) { return value.greet(); } }",
        )
        val usages = route(
            "usages",
            request {
                put("selector", buildJsonObject { put("symbol", "com.example.Base#greet") })
            },
        ).jsonObject
        assertEquals(
            1,
            usages.getValue("summary").jsonObject.getValue("overrides").jsonPrimitive.int,
        )
        val result = route(
            "rename",
            request {
                put("selector", buildJsonObject { put("symbol", "com.example.Child#greet") })
                put("to", "welcome")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "none")
            },
        ).jsonObject

        assertTrue(base.text.contains("welcome"))
        assertTrue(child.text.contains("welcome"))
        assertTrue(consumer.text.contains("value.welcome()"))
        assertEquals(
            "com.example.Base#greet",
            result.getValue("hierarchyRoot").jsonObject.getValue("qualifiedName").jsonPrimitive.content,
        )
        assertEquals(
            setOf(
                "com/example/Base.java",
                "com/example/Child.java",
                "com/example/UseChild.java",
            ),
            result.getValue("changedFiles").jsonArray
                .map {
                    val change = it.jsonObject
                    assertTrue(change.getValue("expected").jsonPrimitive.boolean)
                    change.getValue("path").jsonPrimitive.content
                }
                .toSet(),
        )
    }

    fun testJavaDiamondOverrideRenamesEveryHierarchyRoot() {
        val left = myFixture.addFileToProject(
            "diamond/Left.java",
            "package diamond; public interface Left { String greet(); }",
        )
        val right = myFixture.addFileToProject(
            "diamond/Right.java",
            "package diamond; public interface Right { String greet(); }",
        )
        val both = myFixture.addFileToProject(
            "diamond/Both.java",
            """
                package diamond;
                public class Both implements Left, Right {
                    @Override public String greet() { return "both"; }
                }
            """.trimIndent(),
        )
        val consumer = myFixture.addFileToProject(
            "diamond/UseBoth.java",
            "package diamond; class UseBoth { String call(Both value) { return value.greet(); } }",
        )

        val result = route(
            "rename",
            request {
                put("selector", buildJsonObject { put("symbol", "diamond.Both#greet") })
                put("to", "welcome")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "none")
            },
        ).jsonObject

        assertTrue(left.text.contains("welcome()"))
        assertTrue(right.text.contains("welcome()"))
        assertTrue(both.text.contains("welcome()"))
        assertTrue(consumer.text.contains("value.welcome()"))
        assertEquals(
            "diamond.Left#greet",
            result.getValue("hierarchyRoot").jsonObject
                .getValue("qualifiedName").jsonPrimitive.content,
        )
        assertEquals(
            setOf(
                "diamond/Left.java",
                "diamond/Right.java",
                "diamond/Both.java",
                "diamond/UseBoth.java",
            ),
            result.getValue("changedFiles").jsonArray.map {
                val change = it.jsonObject
                assertTrue(change.getValue("expected").jsonPrimitive.boolean)
                change.getValue("path").jsonPrimitive.content
            }.toSet(),
        )
    }

    fun testKotlinRenameFromOverrideUsesHierarchyRoot() {
        val base = myFixture.addFileToProject(
            "kotlinhierarchy/Greeter.kt",
            "package kotlinhierarchy\ninterface Greeter { fun greet(): String }",
        )
        val child = myFixture.addFileToProject(
            "kotlinhierarchy/Child.kt",
            """
                package kotlinhierarchy
                class Child : Greeter {
                    override fun greet(): String = "child"
                }
            """.trimIndent(),
        )
        val consumer = myFixture.addFileToProject(
            "kotlinhierarchy/UseChild.kt",
            "package kotlinhierarchy\nfun call(value: Child): String = value.greet()",
        )
        val result = route(
            "rename",
            request {
                put(
                    "selector",
                    buildJsonObject { put("symbol", "kotlinhierarchy.Child#greet") },
                )
                put("to", "welcome")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "none")
            },
        ).jsonObject

        assertTrue(base.text.contains("fun welcome"))
        assertTrue(child.text.contains("override fun welcome"))
        assertTrue(consumer.text.contains("value.welcome()"))
        assertEquals(
            "kotlinhierarchy.Greeter.greet",
            result.getValue("hierarchyRoot").jsonObject
                .getValue("qualifiedName").jsonPrimitive.content,
        )
        assertEquals(
            setOf(
                "kotlinhierarchy/Greeter.kt",
                "kotlinhierarchy/Child.kt",
                "kotlinhierarchy/UseChild.kt",
            ),
            result.getValue("changedFiles").jsonArray
                .map {
                    val change = it.jsonObject
                    assertTrue(change.getValue("expected").jsonPrimitive.boolean)
                    change.getValue("path").jsonPrimitive.content
                }
                .toSet(),
        )
    }

    fun testRenamesJavaDeclarationKindsByPosition() {
        val file = myFixture.addFileToProject(
            "matrix/Matrix.java",
            """
                package matrix;
                public class Matrix<ClassType> {
                    class Nested {}
                    interface InnerContract {}
                    enum State { READY }
                    @interface Marker {}
                    record Entry<RecordType>(RecordType data) {}
                    int fieldValue;
                    <MethodType> MethodType map(MethodType parameterValue) {
                        MethodType localValue = parameterValue;
                        return localValue;
                    }
                }
            """.trimIndent(),
        )

        renameAt(file, "matrix/Matrix.java", "Nested", "CLASS", "Inner")
        renameAt(file, "matrix/Matrix.java", "InnerContract", "INTERFACE", "Contract")
        renameAt(file, "matrix/Matrix.java", "State", "ENUM", "Phase")
        renameAt(file, "matrix/Matrix.java", "READY", "ENUM_CONSTANT", "ACTIVE")
        renameAt(file, "matrix/Matrix.java", "Marker", "ANNOTATION", "Tag")
        renameAt(file, "matrix/Matrix.java", "Entry", "RECORD", "Pair")
        renameAt(file, "matrix/Matrix.java", "ClassType", "TYPE_PARAMETER", "ElementType")
        renameAt(file, "matrix/Matrix.java", "RecordType", "TYPE_PARAMETER", "ValueType")
        renameAt(file, "matrix/Matrix.java", "fieldValue", "FIELD", "total")
        renameAt(file, "matrix/Matrix.java", "MethodType", "TYPE_PARAMETER", "ResultType")
        renameAt(file, "matrix/Matrix.java", "parameterValue", "PARAMETER", "input")
        renameAt(file, "matrix/Matrix.java", "localValue", "LOCAL", "result")

        assertTrue(file.text.contains("class Inner"))
        assertTrue(file.text, file.text.contains("enum Phase"))
        assertTrue(file.text, file.text.contains("ACTIVE"))
        assertTrue(file.text.contains("<ResultType> ResultType map(ResultType input)"))
        assertTrue(file.text.contains("ResultType result = input"))
    }

    fun testRenamesKotlinDeclarationKindsByPosition() {
        val file = myFixture.addFileToProject(
            "matrix/Declarations.kt",
            """
                package matrix
                typealias Label = String
                interface Contract
                object Registry
                fun String.renderValue(): String = this
                fun useExtension(): String = "value".renderValue()
                class Box<ItemType>(val itemValue: ItemType) {
                    companion object Factory {
                        val markerValue = 1
                    }
                    fun transform(parameterValue: ItemType): ItemType {
                        val localValue = parameterValue
                        return localValue
                    }
                }
            """.trimIndent(),
        )

        renameAt(file, "matrix/Declarations.kt", "Label", "TYPE_ALIAS", "Title")
        renameAt(file, "matrix/Declarations.kt", "Contract", "INTERFACE", "Agreement")
        renameAt(file, "matrix/Declarations.kt", "Registry", "OBJECT", "Catalog")
        renameAt(file, "matrix/Declarations.kt", "renderValue", "FUNCTION", "displayValue")
        renameAt(file, "matrix/Declarations.kt", "Box", "CLASS", "Container")
        renameAt(file, "matrix/Declarations.kt", "ItemType", "TYPE_PARAMETER", "ElementType")
        renameAt(file, "matrix/Declarations.kt", "itemValue", "PROPERTY", "item")
        renameAt(file, "matrix/Declarations.kt", "Factory", "COMPANION_OBJECT", "Creator")
        renameAt(file, "matrix/Declarations.kt", "markerValue", "PROPERTY", "marker")
        renameAt(file, "matrix/Declarations.kt", "parameterValue", "PARAMETER", "input")
        renameAt(file, "matrix/Declarations.kt", "localValue", "LOCAL", "result")

        assertTrue(file.text.contains("typealias Title"))
        assertTrue(file.text.contains("class Container<ElementType>(val item: ElementType)"))
        assertTrue(file.text.contains("companion object Creator"))
        assertTrue(file.text.contains("\"value\".displayValue()"))
        assertTrue(file.text.contains("val result = input"))
    }

    fun testRenamesAcrossJavaAndKotlinReferences() {
        val javaType = myFixture.addFileToProject(
            "cross/Shared.java",
            "package cross; public class Shared {}",
        )
        val kotlinConsumer = myFixture.addFileToProject(
            "cross/UseShared.kt",
            "package cross\nfun useShared(value: Shared): Shared = value",
        )
        route(
            "rename",
            request {
                put("selector", buildJsonObject { put("symbol", "cross.Shared") })
                put("to", "Common")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "none")
            },
        )

        assertEquals("Common.java", javaType.virtualFile.name)
        assertTrue(kotlinConsumer.text.contains("value: Common"))

        val kotlinType = myFixture.addFileToProject(
            "cross/KModel.kt",
            "package cross\nclass KModel",
        )
        val javaConsumer = myFixture.addFileToProject(
            "cross/UseKModel.java",
            "package cross; class UseKModel { KModel value; }",
        )
        renameAt(kotlinType, "cross/KModel.kt", "KModel", "CLASS", "KAccount")

        assertTrue(javaConsumer.text.contains("KAccount value"))
    }

    fun testResolvesAndRenamesKotlinFunction() {
        val file = myFixture.addFileToProject(
            "com/example/Greeter.kt",
            """
                package com.example
                class Greeter {
                    fun greet(name: String): String = "Hello, " + name
                }
                fun call(greeter: Greeter): String = greeter.greet("agent")
            """.trimIndent(),
        )
        val resolve = route(
            "resolve",
            request {
                put("selector", buildJsonObject {
                    put("symbol", "com.example.Greeter#greet")
                    put("expect", "greet:FUNCTION")
                })
            },
        ).jsonObject

        assertEquals("FUNCTION", resolve.getValue("kind").jsonPrimitive.content)

        val renamed = route(
            "rename",
            request {
                put("selector", buildJsonObject {
                    put("symbol", "com.example.Greeter#greet")
                    put("expect", "greet:FUNCTION")
                })
                put("to", "welcome")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "none")
            },
        ).jsonObject

        assertEquals("APPLIED", renamed.getValue("status").jsonPrimitive.content)
        assertTrue(file.text.contains("fun welcome"))
        assertTrue(file.text.contains("greeter.welcome"))
    }

    fun testResolvesAndRenamesPythonMethod() {
        val file = myFixture.addFileToProject(
            "example/greeter.py",
            """
                class Greeter:
                    def greet(self, name):
                        return "Hello, " + name

                def call(greeter):
                    return greeter.greet("agent")
            """.trimIndent(),
        )
        assertEquals("Python", file.language.id)
        val selector = buildJsonObject {
            put("file", "example/greeter.py")
            put("line", 2)
            put("col", 9)
            put("expect", "greet:FUNCTION")
        }
        val resolve = route(
            "resolve",
            request { put("selector", selector) },
        ).jsonObject

        assertEquals("FUNCTION", resolve.getValue("kind").jsonPrimitive.content)
        assertTrue(resolve.getValue("warnings").jsonArray.isNotEmpty())

        val renamed = route(
            "rename",
            request {
                put("selector", selector)
                put("to", "welcome")
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "none")
            },
        ).jsonObject

        assertEquals("APPLIED", renamed.getValue("status").jsonPrimitive.content)
        assertTrue(renamed.getValue("warnings").jsonArray.isNotEmpty())
        assertTrue(file.text.contains("def welcome"))
        assertTrue(file.text.contains("greeter.welcome"))
    }

    fun testPythonRenameFromOverrideUsesHierarchyRoot() {
        val base = myFixture.addFileToProject(
            "pythonhierarchy/base.py",
            """
                class Greeter:
                    def greet(self):
                        return "base"
            """.trimIndent(),
        )
        val child = myFixture.addFileToProject(
            "pythonhierarchy/child.py",
            """
                from .base import Greeter

                class Child(Greeter):
                    def greet(self):
                        return "child"
            """.trimIndent(),
        )
        val consumer = myFixture.addFileToProject(
            "pythonhierarchy/consumer.py",
            "from .child import Child\n\nvalue = Child().greet()",
        )

        val result = renameAt(
            child,
            "pythonhierarchy/child.py",
            "greet",
            "FUNCTION",
            "welcome",
        )

        assertTrue(base.text.contains("def welcome"))
        assertTrue(child.text.contains("def welcome"))
        assertTrue(consumer.text.contains("Child().welcome()"))
        assertTrue(
            result.getValue("changedFiles").jsonArray
                .map { it.jsonObject }
                .single {
                    it.getValue("path").jsonPrimitive.content ==
                        "pythonhierarchy/consumer.py"
                }
                .getValue("expected").jsonPrimitive.boolean,
        )
        assertEquals(
            "pythonhierarchy.base.Greeter.greet",
            result.getValue("hierarchyRoot").jsonObject
                .getValue("qualifiedName").jsonPrimitive.content,
        )
    }

    fun testPythonHierarchyConflictReportsWithoutApplying() {
        val base = myFixture.addFileToProject(
            "pythonconflict/base.py",
            "class Greeter:\n    def greet(self):\n        return 'base'",
        )
        val child = myFixture.addFileToProject(
            "pythonconflict/child.py",
            """
                from .base import Greeter

                class Child(Greeter):
                    def greet(self):
                        return "child"

                    def welcome(self):
                        return "existing"
            """.trimIndent(),
        )
        val document = child.viewProvider.document!!
        val offset = child.text.indexOf("greet")
        val line = document.getLineNumber(offset)

        val error = expectRefactor {
            route(
                "rename",
                request {
                    put("selector", buildJsonObject {
                        put("file", "pythonconflict/child.py")
                        put("line", line + 1)
                        put("col", offset - document.getLineStartOffset(line) + 1)
                        put("expect", "greet:FUNCTION")
                    })
                    put("to", "welcome")
                    put("dryRun", false)
                    put("forceNonSource", false)
                    put("diff", "none")
                },
            )
        }

        assertEquals("NEEDS_REVIEW", error.symbolicCode)
        assertTrue(error.details.getValue("conflicts").jsonArray.isNotEmpty())
        assertTrue(base.text.contains("def greet"))
        assertTrue(child.text.contains("def greet"))
        assertEquals(1, "def welcome".toRegex().findAll(child.text).count())
    }

    fun testPythonMultipleInheritanceRenamesEveryHierarchyRoot() {
        val left = myFixture.addFileToProject(
            "pythonmultiple/left.py",
            "class Left:\n    def greet(self):\n        return 'left'",
        )
        val right = myFixture.addFileToProject(
            "pythonmultiple/right.py",
            "class Right:\n    def greet(self):\n        return 'right'",
        )
        val child = myFixture.addFileToProject(
            "pythonmultiple/child.py",
            """
                from .left import Left
                from .right import Right

                class Child(Left, Right):
                    def greet(self):
                        return "child"
            """.trimIndent(),
        )
        val consumer = myFixture.addFileToProject(
            "pythonmultiple/consumer.py",
            "from .child import Child\n\nvalue = Child().greet()",
        )

        val result = renameAt(
            child,
            "pythonmultiple/child.py",
            "greet",
            "FUNCTION",
            "welcome",
        )

        assertTrue(left.text.contains("def welcome"))
        assertTrue(right.text.contains("def welcome"))
        assertTrue(child.text.contains("def welcome"))
        assertTrue(consumer.text.contains("Child().welcome()"))
        assertEquals(
            "pythonmultiple.left.Left.greet",
            result.getValue("hierarchyRoot").jsonObject
                .getValue("qualifiedName").jsonPrimitive.content,
        )
        assertTrue(
            result.getValue("changedFiles").jsonArray
                .all { it.jsonObject.getValue("expected").jsonPrimitive.boolean },
        )
    }

    fun testRenamesPythonVariablesParametersAndLocals() {
        val file = myFixture.addFileToProject(
            "matrix/variables.py",
            """
                module_value = 1
                class Worker:
                    def calculate(self, parameter_value):
                        local_value = parameter_value + module_value
                        return local_value
            """.trimIndent(),
        )

        renameAt(file, "matrix/variables.py", "module_value", "VARIABLE", "default_value")
        renameAt(file, "matrix/variables.py", "Worker", "CLASS", "Calculator")
        renameAt(file, "matrix/variables.py", "parameter_value", "PARAMETER", "input_value")
        renameAt(file, "matrix/variables.py", "local_value", "VARIABLE", "result_value")

        assertTrue(file.text.contains("default_value = 1"))
        assertTrue(file.text.contains("class Calculator"))
        assertTrue(file.text.contains("result_value = input_value + default_value"))
    }

    fun testPythonRenameUpdatesRelativeImportsAndAnnotationsOnlyForTargetModule() {
        val model = myFixture.addFileToProject(
            "pkg/model.py",
            "class User:\n    pass",
        )
        val consumer = myFixture.addFileToProject(
            "pkg/consumer.py",
            "from .model import User\n\ndef load(value: User) -> User:\n    return value",
        )
        val other = myFixture.addFileToProject(
            "other/model.py",
            "class User:\n    pass",
        )

        renameAt(model, "pkg/model.py", "User", "CLASS", "Account")

        assertTrue(model.text.contains("class Account"))
        assertTrue(consumer.text.contains("import Account"))
        assertTrue(consumer.text.contains("value: Account"))
        assertTrue(consumer.text.contains("-> Account"))
        assertTrue(other.text.contains("class User"))
    }

    private fun addJavaFixture(): com.intellij.psi.PsiFile {
        val user = myFixture.addFileToProject(
            "com/example/User.java",
            """
                package com.example;
                public class User {
                    public String name;
                    public String greet() { return name; }
                    public int convert(int value) { return value; }
                    public int convert(long value) { return (int) value; }
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/example/Consumer.java",
            """
                package com.example;
                public class Consumer {
                    String read(User user) { return user.name; }
                }
            """.trimIndent(),
        )
        return user
    }

    private fun withPhysicalJavaFile(
        relativePath: String,
        contents: String,
        action: (com.intellij.openapi.vfs.VirtualFile) -> Unit,
    ) {
        val directory = java.nio.file.Files.createTempDirectory("refactor-sync-test-")
        val path = directory.resolve(relativePath)
        java.nio.file.Files.createDirectories(path.parent)
        java.nio.file.Files.writeString(path, contents)
        val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(directory)!!
        com.intellij.testFramework.PsiTestUtil.addSourceContentToRoots(myFixture.module, root)
        try {
            val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(path)!!
            action(file)
        } finally {
            com.intellij.testFramework.PsiTestUtil.removeContentEntry(myFixture.module, root)
            directory.toFile().deleteRecursively()
        }
    }

    private fun renameAt(
        file: com.intellij.psi.PsiFile,
        path: String,
        oldName: String,
        kind: String,
        newName: String,
    ): JsonObject {
        val document = file.viewProvider.document!!
        val offset = document.text.indexOf(oldName)
        assertTrue("$oldName was not found in $path", offset >= 0)
        val line = document.getLineNumber(offset)
        val result = route(
            "rename",
            request {
                put("selector", buildJsonObject {
                    put("file", path)
                    put("line", line + 1)
                    put("col", offset - document.getLineStartOffset(line) + 1)
                    put("expect", "$oldName:$kind")
                })
                put("to", newName)
                put("dryRun", false)
                put("forceNonSource", false)
                put("diff", "none")
            },
        ).jsonObject
        assertEquals("APPLIED", result.getValue("status").jsonPrimitive.content)
        return result
    }

    private fun renameRequest(dryRun: Boolean): JsonObject = request {
        put("selector", buildJsonObject { put("symbol", "com.example.User#name") })
        put("to", "displayName")
        put("dryRun", dryRun)
        put("forceNonSource", false)
        put("diff", "none")
    }

    private fun request(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("project", project.basePath!!)
            extra()
        }

    private fun route(method: String, params: JsonObject): JsonElement =
        PlatformTestUtil.waitForFuture(
            ApplicationManager.getApplication().executeOnPooledThread<Any> {
                try {
                    RefactorOperationRouter().route(method, params)
                } catch (error: Throwable) {
                    error
                }
            },
            30_000,
        ).let { result ->
            if (result is Throwable) throw result
            result as JsonElement
        }

    private fun expectRefactor(action: () -> Unit): RefactorException {
        try {
            action()
        } catch (error: RefactorException) {
            return error
        }
        fail("expected RefactorException")
        error("unreachable")
    }
}
