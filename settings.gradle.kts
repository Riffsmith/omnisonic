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

rootProject.name = "omnisonic"

include(":app")
include(":core-model")
include(":core-data")
include(":core-network")
include(":provider-api")
include(":provider-navidrome")
include(":provider-local")
include(":playback")
include(":playback-api")
include(":style")
include(":feature-library")
include(":feature-search")
include(":feature-downloads")
include(":feature-profiles")
include(":feature-settings")
include(":baseline-profile")
