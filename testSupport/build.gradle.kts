// :testSupport — the reducer harness and hand-written-run helpers shared by :engine and :replay tests
// (the architecture's test-only module). Depends on :core/:engine main; nothing depends on it except
// other modules' test source sets.
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
    }
}
