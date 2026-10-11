package com.jakubfronczyk.northform.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile

/**
 * The app's Documents directory, visible in the Files app under "Northform" because `Info.plist`
 * sets `UIFileSharingEnabled` and `LSSupportsOpeningDocumentsInPlace` (project.yml). Debug builds
 * write each run there as `Recordings/<start>-<type>.jsonl` (D103 of the Swift build); copying one
 * off the phone into `Fixtures/private/` is the step-7 flow.
 */
@OptIn(ExperimentalForeignApi::class)
object Documents {
    private val root: NSURL
        get() = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as NSURL

    fun recordingPath(fileName: String): String {
        val dir = root.URLByAppendingPathComponent("Recordings", isDirectory = true)!!
        NSFileManager.defaultManager.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir.URLByAppendingPathComponent(fileName)!!.path!!
    }

    /** Whole-file atomic write, as the Swift `write(to:atomically:)`. */
    fun write(path: String, text: String) {
        (text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}

/**
 * The paired sensor, remembered so the app reconnects at launch (`AppDeps.swift:20,40-42`; SU3, D52:
 * one sensor). Seeds the link at boot; written only by `LinkSensorDetails`' one collector on the link's
 * pairing (adapters/01), never by a screen. The name is kept so the Sensor screen can say "Polar Verity Sense · A1B2C3D4" before it is seen again.
 */
object PairedSensor {
    private const val KEY = "pairedSensorID"
    private const val NAME_KEY = "pairedSensorName"
    private val defaults get() = NSUserDefaults.standardUserDefaults
    var id: String?
        get() = defaults.stringForKey(KEY)
        set(value) = defaults.setObject(value, forKey = KEY)
    var name: String?
        get() = defaults.stringForKey(NAME_KEY)
        set(value) = defaults.setObject(value, forKey = NAME_KEY)
}
