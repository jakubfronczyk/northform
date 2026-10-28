package com.jakubfronczyk.northform.replay

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.getenv

/**
 * Kotlin/Native test binaries carry no resources and the simulator does not start them in the module
 * directory, so Gradle passes the absolute resources path in `NORTHFORM_REPLAY_RESOURCES` (replay/build.gradle.kts).
 */
@OptIn(ExperimentalForeignApi::class)
actual fun resourceText(path: String): String {
    val root = getenv("NORTHFORM_REPLAY_RESOURCES")?.toKString()
        ?: error("NORTHFORM_REPLAY_RESOURCES is unset; run through :replay:iosSimulatorArm64Test")
    return NSString.stringWithContentsOfFile("$root/$path", encoding = NSUTF8StringEncoding, error = null)
        ?: error("missing test resource $root/$path")
}

/**
 * Kotlin/Native's `Double.toString` breaks an exact 17-digit tie the other way from the JVM and Swift
 * (`1.1629104614257813` where they print `…812`, in run-2-walk); both strings parse to the same Double.
 */
actual val swiftByteParity: Boolean = false

/** The simulator has no environment to point at a private file. */
actual fun swiftFixtureText(): String? = null
