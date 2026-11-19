import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.github.alexeyleping"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localPropertiesFile = rootProject.file("local.properties")
val localIdeFromFile: String? = if (localPropertiesFile.exists()) {
    Properties().apply { localPropertiesFile.inputStream().use { load(it) } }.getProperty("localIdePath")
} else {
    null
}
val localIdePath: String? = providers.gradleProperty("localIdePath").orNull ?: localIdeFromFile
val targetIdeVersion = "2026.2"

dependencies {
    intellijPlatform {
        if (localIdePath != null) {
            local(localIdePath)
        } else {
            intellijIdea(targetIdeVersion)
        }
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("JUnit")
        testFramework(TestFrameworkType.Platform)
    }

    listOf(
        "io.ktor:ktor-server-core:3.4.1",
        "io.ktor:ktor-server-netty:3.4.1",
        "io.ktor:ktor-server-content-negotiation:3.4.1",
        "io.ktor:ktor-serialization-kotlinx-json:3.4.1"
    ).forEach { dependency ->
        implementation(dependency) {
            exclude(group = "org.slf4j")
            exclude(group = "ch.qos.logback")
        }
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = "Debug MCP"
        version = "1.1.0"
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
            localIdePath?.let { local(it) }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    maxHeapSize = "2g"
    jvmArgs("-XX:MaxMetaspaceSize=768m", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=build/test-heapdumps")
    systemProperty("idea.force.use.core.classloader", "true")

    systemProperty("idea.load.plugins.id", "com.github.alexeyleping.intellij-debug-mcp,com.intellij.java,org.jetbrains.kotlin,JUnit")
}
