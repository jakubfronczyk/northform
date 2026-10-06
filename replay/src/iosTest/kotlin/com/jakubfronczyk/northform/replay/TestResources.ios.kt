package com.jakubfronczyk.northform.replay

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * Kotlin/Native test binaries carry no resources; read the file from the module directory, which is
 * the working directory Gradle starts the test executable in. TODO(walking-skeleton): verify on the
 * macOS CI job; `just test` runs the JVM tests only.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun resourceText(path: String): String =
    NSString.stringWithContentsOfFile("src/commonMain/resources/$path", encoding = NSUTF8StringEncoding, error = null)
        ?: error("missing test resource $path (cwd must be the :replay module directory)")
