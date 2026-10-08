// :app — composition root + the iOS framework the Xcode shell embeds (D15).
// Direct integration: https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html
//   `embedAndSignAppleFrameworkForXcode` is registered only because `binaries.framework {}` is declared here.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// A Kotlin/Native test runs inside the simulator sandbox with no bundle, so the replay fixture the
// flow test needs is generated into a test-source constant from replay's resources at build time.
val generateTestFixtureSources = tasks.register("generateTestFixtureSources") {
    val fixture = rootProject.file("replay/src/commonMain/resources/fixtures/straight-run-3min.jsonl")
    val out = layout.buildDirectory.dir("generated/testFixtures")
    inputs.file(fixture)
    outputs.dir(out)
    doLast {
        val quotes = "\"\"\""
        out.get().asFile.resolve("BundledFixtureText.kt").apply { parentFile.mkdirs() }.writeText(
            """
            |package com.jakubfronczyk.northform.app
            |
            |/** Generated from replay/src/commonMain/resources/fixtures by :app:generateTestFixtureSources. */
            |object BundledFixtureText {
            |    val STRAIGHT_RUN_3MIN: String = $quotes${fixture.readText()}$quotes
            |}
            |""".trimMargin(),
        )
    }
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        // os_log needs its format string compiled by clang (see the .def); the only C in the build.
        target.compilations.getByName("main").cinterops.create("oslog") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/oslog.def"))
        }
        target.binaries.framework {
            baseName = "Northform"
            isStatic = false // dynamic: Gradle embeds the fresh dylib each build; a static one is invisible to Xcode's relink (project.yml)
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
            implementation(project(":replay")) // simulator runs replay a bundled fixture (D3: a runtime choice)
            implementation(project(":composeApp"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        iosTest {
            kotlin.srcDir(generateTestFixtureSources)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":testSupport")) // RecordedAlerts, virtualWallClock, tap
            }
        }
    }
}
