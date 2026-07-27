import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

val junitVersion = "4.13.2"

configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    testImplementation("junit:junit:$junitVersion")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.2.0.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("org.toml.lang")
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("com.intellij.spring")
        bundledPlugin("com.intellij.javaee")
        bundledPlugin("com.intellij.persistence")
        compatiblePlugin("PythonCore")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        // The two watcher-health APIs are intentionally pinned and documented in
        // internal-api-registry.md. Every other verifier category remains fatal.
        failureLevel.set(
            FailureLevel.ALL.filterNot { it == FailureLevel.INTERNAL_API_USAGES },
        )
    }
}

tasks.test {
    environment(
        "REFACTOR_AGENT_HOME",
        layout.buildDirectory.dir("test-descriptors").get().asFile.absolutePath,
    )
}
