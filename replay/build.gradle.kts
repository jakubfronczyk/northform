// :replay — the fixture codec (frozen v1 format), replay types, the in-memory store, and the bundled
// anonymized fixtures (src/commonMain/resources/fixtures). Ships in every build (simulator default).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    // The test binaries run the live session on the SQLDelight store, so they link the system SQLite (see app/build.gradle.kts).
    listOf(iosArm64(), iosSimulatorArm64()).forEach { it.binaries.all { linkerOpts("-lsqlite3") } }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":engine"))
            implementation(libs.kotlinx.serialization.json) // JsonElement tree only; no @Serializable plugin needed
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test) // the live session on a virtual clock
            implementation(project(":testSupport"))
            implementation(project(":persistence")) // the live session on the SQLDelight store
        }
    }
}
