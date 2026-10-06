// :replay — the fixture codec (frozen v1 format), replay types, the in-memory store, and the bundled
// anonymized fixtures (src/commonMain/resources/fixtures). Ships in every build (simulator default).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":engine"))
            implementation(libs.kotlinx.serialization.json) // JsonElement tree only; no @Serializable plugin needed
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":testSupport"))
        }
    }
}
