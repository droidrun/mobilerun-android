// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    compilerOptions {
        optIn.add("ai.mobilerun.stream.protocol.InternalStreamApi")
    }

    sourceSets {
        commonMain.dependencies {
            // Shared opt-in marker and internal transport types.
            api(project(":protocol"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
