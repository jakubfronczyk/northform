// Northform — Kotlin Multiplatform. Module graph per D15 (architecture.md → "Gradle module graph").
// The dependency rule is enforced here: a module can only see what its build file declares.
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "northform"

include(
    ":core",        // Model · Ports · Metrics · GATT parsers        (pure, jvm + ios)
    ":engine",      // reducer · session mailbox · SensorLink        (pure, jvm + ios)
    ":persistence", // SQLDelight store                              (spike: skeleton only)
    ":replay",      // fixture codec + replay adapters               (spike: skeleton only)
    ":adapters",    // iosMain: CoreBluetooth, location, alerts, Live Activity bridge
    ":composeApp",  // Compose Multiplatform UI                       (never sees :adapters)
    ":app",         // composition root + the iOS framework (umbrella)
)
