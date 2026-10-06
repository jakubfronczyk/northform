// :core — Model · Ports · Metrics · GATT parsers. Depends on nothing platform (D3, D15).
// jvm() exists so commonTest runs on ubuntu in seconds; the iOS targets feed the framework.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core) // Flow in the port signatures
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
