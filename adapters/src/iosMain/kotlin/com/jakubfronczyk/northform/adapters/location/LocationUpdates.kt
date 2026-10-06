package com.jakubfronczyk.northform.adapters.location

import platform.CoreLocation.CLLocation

/**
 * The GPS seam (D10). Swift implements this — the ONLY reason it exists is that
 * `CLLocationUpdate.liveUpdates(_:)` is Swift-only (a Swift `struct`, no Obj-C surface:
 * https://developer.apple.com/documentation/corelocation/cllocationupdate) and its `stationary` flag is
 * a product input (D102/D107 distance rules). Callback-shaped on purpose: no `suspend`, no `Flow`, so
 * a Swift `NSObject` subclass can conform to the exported `@protocol`.
 *
 * Contract for the Swift side (dumb hands): `start` begins forwarding every update as
 * `sink.onUpdate(location, stationary)`; `stop` cancels; if the Swift async sequence ends or throws,
 * call `sink.onEnded(message)` once — restart policy (D101 backoff) is Kotlin's, in `LiveLocation`.
 */
interface LocationUpdates {
    fun start(sink: LocationSink)
    fun stop()
}

interface LocationSink {
    fun onUpdate(location: CLLocation, stationary: Boolean)
    fun onEnded(message: String?)
}
