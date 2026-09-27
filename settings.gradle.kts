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
        maven("https://jitpack.io") // libsu (topjohnwu)
    }
}

rootProject.name = "pwndroid"

include(":app")
include(":core:model")
include(":core:common")
include(":core:radio")
include(":core:brain")
include(":core:mood")
include(":core:session")
include(":core:capture")
include(":core:identity")
include(":core:pwngrid")
include(":data")
include(":plugins:api")
include(":plugins:builtin")
include(":feature:display")
include(":feature:webapi")