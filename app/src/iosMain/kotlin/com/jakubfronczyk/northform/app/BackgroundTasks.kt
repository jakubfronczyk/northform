package com.jakubfronczyk.northform.app

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
 * A background task already begun: ended exactly once, by whichever comes first — [run]'s work finishing,
 * or iOS expiring the task. Apple requires `endBackgroundTask` *inside* the expiration handler (the process
 * may be suspended the moment the handler returns), so expiry ends the task right there, on the handler's
 * thread, and only then cancels the work; the normal path's end is a no-op after that. The one atomic flag
 * is what makes the two ends race-free across the main thread and the work's dispatcher.
 */
@OptIn(ExperimentalAtomicApi::class)
class HeldTask internal constructor(private val tasks: BackgroundTasks, name: String) {
    private val ended = AtomicBoolean(false)
    private val expired = AtomicBoolean(false)
    private val work = AtomicReference<Job?>(null)
    // begin() runs on the caller's thread before this object is shared; iOS calls the handler later, on the main thread.
    private val task: Long = tasks.begin(name) {
        expired.store(true)
        end()
        work.load()?.cancel()
    }

    private fun end() { if (ended.compareAndSet(false, true)) tasks.end(task) }

    /** Runs [block] inside this task, then ends it (unless expiry already did). Cancelled when iOS expires the task. */
    suspend fun run(block: suspend () -> Unit) {
        try {
            coroutineScope {
                val job = launch { block() }
                work.store(job)
                if (expired.load()) job.cancel() // expired before the work existed
            }
        } finally {
            end()
        }
    }
}

/** Begins a task now, synchronously — before the caller suspends or hops threads, so iOS knows at once. */
fun BackgroundTasks.hold(name: String): HeldTask = HeldTask(this, name)

/** [hold] then [HeldTask.run]: for a caller with no reason to begin earlier than the work. */
suspend fun BackgroundTasks.bracket(name: String, block: suspend () -> Unit) = hold(name).run(block)

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
