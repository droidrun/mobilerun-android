plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    jvm()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        commonMain.dependencies {
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val fixtures = rootProject.layout.projectDirectory.dir("conformance/fixtures")
    inputs.dir(fixtures)
    systemProperty("conformance.fixtures", fixtures.asFile.absolutePath)
}
