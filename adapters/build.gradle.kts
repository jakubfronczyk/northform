// :adapters — the platform "hands" (D8, D10, D11, D14). iOS-only in the spike; androidMain later.
// Depends on :core/:engine, never on :composeApp. No JVM target: nothing here is unit-testable off-device.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":engine"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
