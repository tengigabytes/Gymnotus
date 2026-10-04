// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

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
