plugins {
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.android.library)
}

android {
    namespace = "ai.mobilerun.stream.android"
    compileSdk = 36

    testOptions { unitTests.isIncludeAndroidResources = true }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        // compileSdk 36 is deliberate: newer okhttp/compose lines need 37 and the SDK picks its own baseline.
        disable += "GradleDependency"
        abortOnError = true
    }
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
        optIn.add("ai.mobilerun.stream.core.InternalStreamApi")
    }
}

dependencies {
    api(project(":stream-core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.webrtc)
    implementation(libs.androidx.lifecycle.process)
    // The OkHttpClient is part of StreamConfig.
    api(libs.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    val fixtures = rootProject.layout.projectDirectory.dir("conformance/fixtures")
    inputs.dir(fixtures)
    systemProperty("conformance.fixtures", fixtures.asFile.absolutePath)
}
