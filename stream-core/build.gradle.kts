plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":protocol"))
        }
    }
}
