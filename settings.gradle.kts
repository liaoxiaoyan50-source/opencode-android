// settings.gradle.kts — 根工程配置（功能块与 app/build.gradle.kts 头注释定稿一致；gradle wrapper 8.7+）
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"   // Kotlin 2.0 Compose 编译器
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "OpenCodeMobile"
include(":app")
