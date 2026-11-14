plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.mobilerun.stream.compose"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        disable += "GradleDependency"
    }
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    api(project(":stream-android"))
    api(libs.compose.ui)
    constraints {
        implementation(libs.androidx.lifecycle.runtime) {
            because("Older lifecycle lint checks use APIs removed from the Android Gradle plugin")
        }
    }
}
