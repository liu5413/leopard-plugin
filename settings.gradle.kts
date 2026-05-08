pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.21"
        id("org.jetbrains.intellij.platform") version "2.5.0"
        id("org.jetbrains.changelog") version "2.2.1"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "leopard-plugin"
