package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.replay.Fixture
import com.jakubfronczyk.northform.replay.ReplayScript
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * The bundled fixtures, read from the app bundle (`Replay/BundledFixtures.swift`). The Xcode target
 * copies `replay/src/commonMain/resources/fixtures` as resources (project.yml); a Kotlin framework
 * carries no resources of its own.
 */
object BundleFixtures {
    @OptIn(ExperimentalForeignApi::class)
    fun script(name: String): ReplayScript {
        val path = NSBundle.mainBundle.pathForResource(name, "jsonl") ?: error("fixture $name.jsonl is not in the app bundle")
        val text = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) ?: error("cannot read $path")
        return Fixture.parse(text)
    }
}
