package com.jakubfronczyk.northform.ui.theme

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * The font comes from the app bundle: the Xcode target copies `composeApp/src/commonMain/resources/fonts` as resources
 * (project.yml), as it does the replay fixtures; a Kotlin framework carries no resources of its own.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun readBundledFont(name: String, extension: String): ByteArray {
    val path = NSBundle.mainBundle.pathForResource(name, extension) ?: error("font $name.$extension is not in the app bundle")
    val data = NSData.dataWithContentsOfFile(path) ?: error("cannot read $path")
    return ByteArray(data.length.toInt()).apply { usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
}
