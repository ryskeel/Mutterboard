pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Mutterboard"
include(":app")

// Pastiera's keyboard, built as a library from its own source tree (see
// keyboard/app/mutterboard.gradle.kts for why it has a build file of its own).
include(":keyboard")
project(":keyboard").projectDir = file("keyboard/app")
project(":keyboard").buildFileName = "mutterboard.gradle.kts"
