package com.jakubfronczyk.northform.engine.run

import kotlin.time.Duration.Companion.seconds

/**
 * Pure reducer: no clock, no I/O, no UUID — every input carries its time. `when` is used as an
 * EXPRESSION so adding a case is a compile error (exhaustiveness over sealed types is enforced only
 * in expression position: https://kotlinlang.org/docs/sealed-classes.html#sealed-classes-and-when-expression).
 *
 * SPIKE SUBSET: start/stop, HR-now, HR-lost after 5 s, flush every 5 s with one batch in flight,
 * buffer cleared only on `Persisted`. The full R24–R29 behaviour is the Phase 0/1 port.
 */
object RunReducer {
    val hrLostAfter = 5.seconds
    val flushEvery = 5.seconds

    data class Reduced(val state: RunState, val effects: List<RunEffect>)

    fun reduce(s: RunState, input: RunInput): Reduced = when (input) {
        is RunInput.User -> user(s, input)
        is RunInput.Hr -> if (s.phase is Phase.Active) Reduced(
            s.copy(hrNow = input.sample.bpm, lastHrAt = input.sample.t, hrLost = false, unconfirmedHr = s.unconfirmedHr + input.sample),
            emptyList(),
        ) else Reduced(s, emptyList())
        is RunInput.Fix -> if (s.phase is Phase.Active) Reduced(s.copy(unconfirmedFixes = s.unconfirmedFixes + input.fix), emptyList()) else Reduced(s, emptyList())
        is RunInput.Tick -> tick(s, input)
        is RunInput.Sensor -> Reduced(s, emptyList())
        is RunInput.Backgrounded -> requestFlush(s, input.at)
        is RunInput.Persisted -> if (input.seq == s.inFlight) Reduced(s.copy(inFlight = null, buffer = null), emptyList()) else Reduced(s, emptyList())
        is RunInput.PersistFailed -> if (input.seq == s.inFlight) Reduced(s.copy(inFlight = null), emptyList()) else Reduced(s, emptyList()) // buffer kept for the next try
    }

    private fun user(s: RunState, input: RunInput.User): Reduced = when (input.action) {
        UserAction.Start -> if (s.phase is Phase.Gate) Reduced(s.copy(phase = Phase.Active(input.at), lastFlushAt = input.at), emptyList()) else Reduced(s, emptyList())
        UserAction.Stop -> when (val p = s.phase) {
            is Phase.Active -> {
                // `stop` is emitted BEFORE the final persist so it is durable first (ADR 0006 of the Swift build).
                val flushed = requestFlush(s.copy(phase = Phase.Done(p.startedAt, input.at)), input.at)
                Reduced(flushed.state, listOf(RunEffect.Stop(input.at)) + flushed.effects)
            }
            else -> Reduced(s, emptyList())
        }
        UserAction.Pause, UserAction.Resume -> Reduced(s, emptyList()) // TODO(spike): Phase 0 port
    }

    private fun tick(s: RunState, input: RunInput.Tick): Reduced {
        val p = s.phase as? Phase.Active ?: return Reduced(s, emptyList())
        val since = (s.lastHrAt ?: p.startedAt)
        val lost = input.t - since >= hrLostAfter
        var next = s.copy(hrLost = lost, hrNow = if (lost) null else s.hrNow)
        val effects = mutableListOf<RunEffect>()
        if (lost && !s.hrLost) effects += RunEffect.Alert(AlertKind.HrLost) // TODO(spike): D108 10 s alert rule in the real port
        val due = s.lastFlushAt?.let { input.t - it >= flushEvery } ?: true
        if (due) {
            val r = requestFlush(next, input.t)
            next = r.state; effects += r.effects
        }
        return Reduced(next, effects)
    }

    /** One batch in flight. A flush requested while one is in flight waits; nothing is dropped. */
    private fun requestFlush(s: RunState, at: kotlin.time.Instant): Reduced {
        if (s.inFlight != null || (s.unconfirmedHr.isEmpty() && s.unconfirmedFixes.isEmpty())) return Reduced(s.copy(lastFlushAt = at), emptyList())
        val batch = FlushBatch(s.unconfirmedHr, s.unconfirmedFixes, persistedAt = at)
        val seq = s.nextSeq
        return Reduced(
            s.copy(buffer = batch, inFlight = seq, nextSeq = seq + 1, unconfirmedHr = emptyList(), unconfirmedFixes = emptyList(), lastFlushAt = at),
            listOf(RunEffect.Persist(seq, batch)),
        )
    }
}
