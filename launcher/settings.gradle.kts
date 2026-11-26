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
        // Kotlin's plugins and their markers come from Maven Central only: the Plugin Portal served a different
        // kapt marker .pom than Central's (2026-11-26), which dependency verification rightly refused. One source,
        // the checksums we recorded - never a second trusted copy.
        gradlePluginPortal {
            content {
                excludeGroupByRegex("org\\.jetbrains\\.kotlin.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kids-launcher-mdm"
include(":app")