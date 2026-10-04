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
        // libadb-android is published only here.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("""com\.github\.MuntashirAkon(\..*)?""") }
        }
    }
}

rootProject.name = "Gymnotus"

include(":app")
