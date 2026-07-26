package io.github.glinte.refactor.operations

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RefactorOperationRouterTest : BasePlatformTestCase() {
    fun testStatusReportsOpenProject() {
        val result = RefactorOperationRouter()
            .route("status", JsonObject(emptyMap()))
            .jsonObject
        val projects = result.getValue("projects").jsonArray

        assertEquals(1, result.getValue("protocolVersion").jsonPrimitive.int)
        assertTrue(projects.any { it.jsonObject.getValue("name").jsonPrimitive.content == project.name })
    }
}
