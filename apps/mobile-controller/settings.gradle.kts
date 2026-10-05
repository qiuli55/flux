// Flux 手机端（原生 Android）的 Gradle 设置。
// 依赖仓库：Google Maven（AGP / AndroidX）+ Maven Central（OkHttp / kotlinx）。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "flux-mobile"
include(":app")