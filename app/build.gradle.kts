plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.mobilerun.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.mobilerun.spike"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // arm64 only: the emulator is arm64 and the size delta is measured for arm64.
    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // -Pbaseline=true builds the same app shell without the WebRTC dependency (APK size delta).
    val baseline = providers.gradleProperty("baseline").isPresent
    sourceSets.getByName("main").kotlin.directories.add(if (baseline) "src/baseline/java" else "src/spike/java")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    if (!providers.gradleProperty("baseline").isPresent) {
        implementation("io.github.webrtc-sdk:android:144.7559.15")
    }
}
