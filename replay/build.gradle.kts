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

// A Kotlin/Native test runs in the simulator (`simctl spawn`), not in this module's directory, and its binary
// carries no resources. The simulator shares the host filesystem, so hand the test the absolute path of the
// fixtures; `simctl` passes a `SIMCTL_CHILD_`-prefixed variable to the spawned process without the prefix.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_NORTHFORM_REPLAY_RESOURCES", file("src/commonMain/resources").absolutePath)
}
