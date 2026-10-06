// :persistence — SQLDelight (D7, D12). Spike: schema skeleton + the native-driver factory with
// synchronous=FULL. The store implementation and crash-resume parity are Phase 0 step 6.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.sqldelight.runtime)
        }
        iosMain.dependencies { implementation(libs.sqldelight.native.driver) }
        jvmMain.dependencies { implementation(libs.sqldelight.sqlite.driver) } // in-memory tests on the JVM
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

sqldelight {
    databases {
        create("NorthformDb") {
            packageName.set("com.jakubfronczyk.northform.persistence")
        }
    }
}
