// The Spotify engine spike (docs/testing/spotify-spike.md): a throwaway, standalone Gradle project.
// Not part of launcher/ or music/, and not built by their CI jobs (.github/workflows/spotify-spike.yml,
// workflow_dispatch only).

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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vibb-spotify-spike"
include(":app")
