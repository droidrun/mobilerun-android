plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The transport interfaces and the session expose protocol types.
            api(project(":protocol"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
