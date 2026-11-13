plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    jvm()

    compilerOptions {
        allWarningsAsErrors.set(true)
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val fixtures = rootProject.layout.projectDirectory.dir("conformance/fixtures")
    inputs.dir(fixtures)
    systemProperty("conformance.fixtures", fixtures.asFile.absolutePath)
}
