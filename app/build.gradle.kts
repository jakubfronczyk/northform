// :app — composition root + the iOS framework the Xcode shell embeds (D15).
// Direct integration: https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html
//   `embedAndSignAppleFrameworkForXcode` is registered only because `binaries.framework {}` is declared here.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Northform"
            isStatic = true
            // Swift may see exactly these (D16): NorthformApp (this module), LocationUpdates/LocationSink and
            // LiveActivityBridge (adapters), LiveActivityContent (engine). Exporting the modules makes their
            // public types appear under the `Northform` module name in Swift.
            export(project(":core"))
            export(project(":engine"))
            export(project(":adapters"))
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(project(":core"))
            api(project(":engine"))
            api(project(":adapters"))
            implementation(project(":composeApp"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
