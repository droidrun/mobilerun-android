import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val conformanceDir = layout.projectDirectory.dir("conformance")

// Shared by every module: JVM-only target for now, explicit API, warnings are errors, and the
// conformance test kit (fixture reader + case runner) compiled into each module's tests.
subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            explicitApi()
            jvmToolchain(17)
            jvm()
            compilerOptions { allWarningsAsErrors.set(true) }

            sourceSets.named("commonTest") {
                kotlin.srcDir(conformanceDir.dir("testkit/common"))
                dependencies {
                    implementation(kotlin("test"))
                    implementation(libs.kotlinx.serialization.json)
                }
            }
            sourceSets.named("jvmTest") {
                kotlin.srcDir(conformanceDir.dir("testkit/jvm"))
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            val fixtures = conformanceDir.dir("fixtures")
            inputs.dir(fixtures)
            systemProperty("conformance.fixtures", fixtures.asFile.absolutePath)
        }
    }
}
