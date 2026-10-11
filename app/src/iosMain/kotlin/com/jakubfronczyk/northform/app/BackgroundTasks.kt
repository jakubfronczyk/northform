package com.jakubfronczyk.northform.app

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * The seam for `UIApplication.beginBackgroundTask`/`endBackgroundTask` (persistence/02): iOS keeps the
 * process running for a named task begun before it suspends the app, for ~30 s, and calls
 * `expirationHandler` when that is up. The app wraps the background flush in one (D94: what is
 * buffered at the moment the app leaves is written and confirmed before the process may sleep).
 *
 * Lives in `:app`, not `:adapters`: it is app-lifecycle wiring beside the `DidEnterBackground`
 * observer `NorthformApp` owns; the adapters are sensor, GPS and alert ports with no `UIApplication`.
 */
interface BackgroundTasks {
    /** Begins a task; [onExpire] is iOS saying "end it now". Returns the handle [end] takes. */
    fun begin(name: String, onExpire: () -> Unit): Long
    fun end(task: Long)

    /** Tests and the simulator: no task is held. */
    object None : BackgroundTasks {
        override fun begin(name: String, onExpire: () -> Unit): Long = 0L
        override fun end(task: Long) {}
    }
}

/**
 * Runs [block] inside one background task: begun first, ended exactly once afterwards, and when iOS
 * expires the task first, [block] is cancelled and the task ended all the same. Structured: the
 * expiration handler only cancels the child; `finally` is the one place `end` is called.
 */
suspend fun BackgroundTasks.bracket(name: String, block: suspend () -> Unit) {
    coroutineScope {
        val work = launch { block() }
        val task = begin(name) { work.cancel() }
        try {
            work.join()
        } finally {
            end(task)
        }
    }
}

/** The real one. Apple documents both calls as safe from any thread. */
class UiBackgroundTasks(private val log: (String) -> Unit = {}) : BackgroundTasks {
    override fun begin(name: String, onExpire: () -> Unit): Long {
        val task = UIApplication.sharedApplication.beginBackgroundTaskWithName(name) {
            log("background task $name expired")
            onExpire()
        }
        log("background task $name begun")
        return task.toLong()
    }

    override fun end(task: Long) {
        if (task.toULong() == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(task.toULong())
        log("background task ended")
    }
}
