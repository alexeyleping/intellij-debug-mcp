import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.github.alexeyleping"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("JUnit")
        testFramework(TestFrameworkType.Platform)
    }

    // Ktor для HTTP-транспорта MCP-сервера
    // SLF4J и logback исключаем — IntelliJ предоставляет их через платформу,
    // бандлинг своей копии вызывает LinkageError при старте сервера
    implementation("io.ktor:ktor-server-core:2.3.12") {
        exclude(group = "org.slf4j")
        exclude(group = "ch.qos.logback")
    }
    implementation("io.ktor:ktor-server-netty:2.3.12") {
        exclude(group = "org.slf4j")
        exclude(group = "ch.qos.logback")
    }
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12") {
        exclude(group = "org.slf4j")
        exclude(group = "ch.qos.logback")
    }
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12") {
        exclude(group = "org.slf4j")
        exclude(group = "ch.qos.logback")
    }

    // JSON (kotlinx-coroutines и kotlin-stdlib предоставляются самой платформой)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = "Debug MCP"
        version = "1.0.1"
    }
    pluginVerification {
        ides {
            ide("IC", "2024.3")
            ide("IC", "2025.1")
        }
    }
}

kotlin {
    jvmToolchain(21)
}
