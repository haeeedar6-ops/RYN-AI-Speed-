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

rootProject.name = "RYN-AI-Speed"

include(":app")
include(":core:common")
include(":core:security")
include(":core:database")
include(":feature:downloads")
include(":feature:browser")
include(":feature:network")
include(":feature:vpn")
