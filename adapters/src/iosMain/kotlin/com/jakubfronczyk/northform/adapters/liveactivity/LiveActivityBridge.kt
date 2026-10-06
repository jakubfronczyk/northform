package com.jakubfronczyk.northform.adapters.liveactivity

import com.jakubfronczyk.northform.engine.run.LiveActivityContent

/**
 * The Live Activity crossing (D11): a hand-written callback, not a Flow. Kotlin `Flow` exports to
 * Swift as an opaque, generic-erased interface (https://kotlinlang.org/docs/native-objc-interop.html),
 * so a typed listener is the dependency-free way across. Swift's `LiveActivityController` implements
 * `LiveActivityListener` and maps the three calls to ActivityKit `request / update / end` — ActivityKit
 * has no Obj-C API, which is the whole reason this file has a Swift counterpart.
 */
interface LiveActivityListener {
    fun onStart(content: LiveActivityContent)
    fun onUpdate(content: LiveActivityContent)
    fun onEnd(content: LiveActivityContent)
}

fun interface Cancellable {
    fun cancel()
}

object LiveActivityBridge {
    private var listener: LiveActivityListener? = null

    /** Swift registers right after `boot()` returns. One listener: there is one lock screen. */
    fun observe(listener: LiveActivityListener): Cancellable {
        this.listener = listener
        return Cancellable { if (this.listener === listener) this.listener = null }
    }

    // Called by the Kotlin session (Phase 1 lane G). Spike step 9 drives these from a timer in NorthformApp.
    fun start(content: LiveActivityContent) = listener?.onStart(content)
    fun update(content: LiveActivityContent) = listener?.onUpdate(content)
    fun end(content: LiveActivityContent) = listener?.onEnd(content)
}
