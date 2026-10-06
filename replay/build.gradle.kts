// :replay — fixture codec + ReplaySensor/ReplayLocation + in-memory store. Ships in every build
// (simulator default). Spike: placeholder module so the graph is real; the codec is Phase 0 step 1 and
// spike step 3 (fixture replay vs the pinned Swift totals).
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
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
