package com.jakubfronczyk.northform.engine.run

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The three ActivityKit calls, decided here (D11). Swift maps them 1:1 to `request / update / end`. */
sealed interface LiveActivityEvent {
    val content: LiveActivityContent

    /** The run started: request the activity. Only ever from the foreground (the Start tap, the Continue tap). */
    data class Start(override val content: LiveActivityContent) : LiveActivityEvent
    data class Update(override val content: LiveActivityContent) : LiveActivityEvent
    /** The run is over: the lock screen stops counting with it. Once per run. */
    data class End(override val content: LiveActivityContent) : LiveActivityEvent
}

/**
 * The lock screen's cadence (D11): one `Start` when the run has content, an `Update` at most every [minInterval]
 * — immediately on a phase change, so a pause shows the second it is tapped — and one `End` when the run is done,
 * after which nothing. Pure: time is data, the caller stamps each snapshot ([on]) and the moment the run was torn
 * down ([close]), so the rule is tested without a clock.
 *
 * Why not `Flow.sample`: the first content, every phase change and the end must each go through at once; a sampler
 * would hold or drop them.
 */
class LiveActivityFeed(private val minInterval: Duration = 5.seconds) {
    private var sentAt: Instant? = null
    private var sent: LiveActivityContent? = null
    private var ended = false

    /** What to tell the lock screen about [snapshot], seen at [now]; null = nothing new worth an update. */
    fun on(snapshot: RunSnapshot, now: Instant): LiveActivityEvent? {
        if (ended) return null
        val content = LiveActivityContent.of(snapshot) ?: return null
        val last = sent
        val event = when {
            last == null -> LiveActivityEvent.Start(content)
            content.phase == LiveActivityPhase.Done -> LiveActivityEvent.End(content)
            content.phase != last.phase || now - sentAt!! >= minInterval -> LiveActivityEvent.Update(content)
            else -> return null
        }
        sent = content
        sentAt = now
        ended = event is LiveActivityEvent.End
        return event
    }

    /**
     * The run was torn down (its screen closed after start, its session ended): end the activity with what it last
     * showed, unless the run already ended it. Null when nothing was ever started. A run that ends by crashing never
     * reaches this; the orphan it leaves is ended at the next launch (Swift `LiveActivityController.attach`).
     */
    fun close(): LiveActivityEvent.End? {
        val last = sent ?: return null
        if (ended) return null
        ended = true
        return LiveActivityEvent.End(last.copy(phase = LiveActivityPhase.Done))
    }
}
