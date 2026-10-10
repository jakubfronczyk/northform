// :persistence — SQLDelight (D7, D12): schema v1, the chunk codec and `SqlRecordingStore`. Lane C owns
// migrations (.sqm) from here on.
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
            implementation(libs.kotlinx.coroutines.core)
        }
        iosMain.dependencies { implementation(libs.sqldelight.native.driver) }
        jvmMain.dependencies { implementation(libs.sqldelight.sqlite.driver) } // in-memory tests on the JVM
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":testSupport")) // RecordingStoreContract
        }
    }
}

sqldelight {
    databases {
        create("NorthformDb") {
            packageName.set("com.jakubfronczyk.northform.persistence")
        }
    }
}
