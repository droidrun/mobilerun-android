import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
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
                kotlin.srcDir(conformanceDir.dir("testkit/shared"))
                dependencies {
                    implementation(kotlin("test"))
                    implementation(libs.kotlinx.coroutines.test)
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

    // All published modules share Central-required metadata.
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            val artifactId = if (project.name == "protocol") "mobilerun-stream-protocol" else "mobilerun-${project.name}"
            coordinates(artifactId = artifactId)
            publishToMavenCentral()
            // The plugin reads signAllPublications: release CI enables it; local/PR builds do not.
            pom {
                name.set(artifactId)
                description.set("Mobilerun device streaming: $artifactId")
                url.set("https://github.com/droidrun/mobilerun-android")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("mobilerun")
                        name.set("Mobilerun")
                        url.set("https://mobilerun.ai")
                    }
                }
                scm {
                    url.set("https://github.com/droidrun/mobilerun-android")
                    connection.set("scm:git:https://github.com/droidrun/mobilerun-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/droidrun/mobilerun-android.git")
                }
            }
        }
    }

    // BCV 0.18.2 detects the legacy Kotlin Android plugin, not AGP's built-in Kotlin.
    // Validate release AAR classes with the same validator used for JVM modules.
    plugins.withId("com.android.library") {
        // BCV's internal dependency bucket/resolver are pinned to validator 0.18.2.
        // Built-in Kotlin needs its metadata reader added to that worker classpath explicitly.
        dependencies.add("bcv-rt-jvm-cp", "org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
        extensions.configure<LibraryAndroidComponentsExtension> {
            onVariants(selector().withBuildType("release")) { variant ->
                val aar = variant.artifacts.get(SingleArtifact.AAR)
                val extractClasses = tasks.register<Sync>("extractReleaseApiClasses") {
                    from(aar.map { zipTree(it) }) { include("classes.jar") }
                    into(layout.buildDirectory.dir("api-input"))
                }
                val apiBuild = tasks.register<KotlinApiBuildTask>("apiBuild") {
                    inputJar.fileProvider(extractClasses.map { it.destinationDir.resolve("classes.jar") })
                    outputApiFile.set(layout.buildDirectory.file("api/${project.name}.api"))
                    runtimeClasspath.from(configurations.named("bcv-rt-jvm-cp-resolver"))
                }
                val apiCheck = tasks.register<KotlinApiCompareTask>("apiCheck") {
                    group = "verification"
                    projectApiFile.set(layout.projectDirectory.file("api/${project.name}.api"))
                    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
                }
                tasks.register<Copy>("apiDump") {
                    from(apiBuild.flatMap { it.outputApiFile })
                    into(layout.projectDirectory.dir("api"))
                }
                tasks.named("check") { dependsOn(apiCheck) }
            }
        }
    }
}

apiValidation {
    ignoredProjects.add("sample")
    // Wire representations and their generated serializers are implementation details.
    ignoredPackages.add("ai.mobilerun.stream.protocol")
    nonPublicMarkers.add("ai.mobilerun.stream.protocol.InternalStreamApi")
    ignoredClasses.addAll(listOf(
        "ai.mobilerun.stream.compose.ComposableSingletons\$DeviceStreamKt",
        "ai.mobilerun.stream.compose.ComposableSingletons\$TaskApprovalKt",
    ))
}
