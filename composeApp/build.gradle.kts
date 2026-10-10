// :composeApp — Compose Multiplatform UI. Sees :core and :engine, NEVER :adapters (D15/D16).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":engine"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.12.1") // androidx @Preview on the fakes (D110)
            implementation(libs.kotlinx.coroutines.core)
        }
        // Screen models are pure functions (table tests); the scripted FakeRunSession runs on runTest's
        // virtual clock. No Compose UI-test dependency: the screens are tested through their models.
        // No JVM target here, so they run on `:composeApp:iosSimulatorArm64Test`.
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
