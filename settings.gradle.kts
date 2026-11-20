// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

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
rootProject.name = "mobilerun-android"
include(":protocol")
include(":stream-core")
include(":stream-android")
include(":stream-compose")
include(":sample")
