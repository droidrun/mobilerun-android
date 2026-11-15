plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.mobilerun.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.mobilerun.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        // Match the SDK's pinned API 36 toolchain; this sample is not a store release.
        disable += setOf("GradleDependency", "OldTargetApi")
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":stream-compose"))
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
}
