package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.ports.FlushBatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The run engine (`Engine/RunReducer.swift`): a pure function of state and input. No clock, no I/O,
 * no randomness, so the same inputs always give the same run — in tests, in the app and on crash
 * resume.
 *
 * Swift `reduce(&state, input) -> [effect]` with `inout` → Kotlin `reduce(state, input): Reduced`
 * returning a NEW immutable state plus the effects. Inside, a private [Draft] holds the working
 * `progress`/`buffer` so each helper reads like its Swift original (file:line cited per method).
 */
object RunReducer {
    val countdown: Duration = 5.seconds
    val cooldown: Duration = 60.seconds
    val persistEvery: Duration = 5.seconds
    val hrLostAfter: Duration = 5.seconds
    /** A sample outside this is invalid and dropped before anything reads it (spec 08, D119). */
    val validHeartRate = 30..230
    /** The HR-lost alert waits until the loss has lasted this long, once per loss (D108). */
    val hrLostAlertAfter: Duration = 10.seconds
    val gpsLostAfter: Duration = 10.seconds
    /** A fix worse than this means GPS is lost. */
    const val gpsLostAccuracy = 50.0
    /** Pace is shown only once it means something (D102). */
    const val splitPaceMinDistance = 50.0
    const val splitDistance = 1000.0

    data class Reduced(val state: RunState, val effects: List<RunEffect>)

    /** `RunReducer.swift:23`. `when` as an expression: a new input case is a compile error (D4). */
    fun reduce(state: RunState, input: RunInput): Reduced {
        val d = Draft(state)
        val handled: Unit = when (input) {
            is RunInput.Tick -> d.tick(input.t)
            is RunInput.Hr -> d.heartRate(input.sample)
            is RunInput.Fix -> d.location(input.fix)
            is RunInput.Sensor -> d.sensor(input.state, input.at)
            is RunInput.User -> d.user(input.action, input.at)
            is RunInput.Backgrounded -> d.requestPersist(input.at)
            is RunInput.Persisted -> d.persisted(input.seq)
            is RunInput.PersistFailed -> d.persistFailed(input.seq)
        }
        return d.result()
    }

    private class Draft(state: RunState) {
        val config = state.config
        var p = state.progress
        var b = state.buffer
        val effects = ArrayList<RunEffect>()

        fun result() = Reduced(RunState(config, p, b), effects)

        // ── Inputs ──────────────────────────────────────────────────────────────────────────

        /** `RunReducer.swift:40`. */
        fun tick(t: Instant) {
            p.lastTick?.let { if (t < it) return } // late input: dropped
            p = p.copy(lastTick = t)
            when (val phase = p.phase) {
                RunPhase.Gate -> return
                is RunPhase.Countdown -> {
                    if (t >= phase.endsAt) {
                        begin(phase.endsAt)
                    } else {
                        // Swift `Int(interval)` truncates toward zero, as `inWholeSeconds` does.
                        val due = (t - (phase.endsAt - countdown)).inWholeSeconds.toInt()
                        while (p.countdownTicksSent <= due) {
                            p = p.copy(countdownTicksSent = p.countdownTicksSent + 1)
                            effects += RunEffect.PlayAlert(Alert.CountdownTick)
                        }
                    }
                    return
                }
                is RunPhase.Cooldown -> if (t >= phase.until) {
                    // Ended at `until`; we learn it at `t`, which is the heartbeat.
                    endCooldown(at = phase.until, persistAt = t, source = EventSource.Auto)
                }
                RunPhase.Done -> {
                    if (b.inFlight == null && !b.isEmpty) requestPersist(t)
                    return
                }
                RunPhase.Active, is RunPhase.Paused -> Unit
            }

            val startedAt = p.startedAt
            if (startedAt != null && p.stoppedAt == null) {
                val lastHrTime = p.lastHr?.t ?: startedAt
                if (!p.hrLost && t - lastHrTime >= hrLostAfter) {
                    p = p.copy(hrLost = true)
                    record(EventKind.HrLost, t)
                }
                if (p.hrLost && !p.hrLostAlerted && t - lastHrTime >= hrLostAlertAfter) {
                    p = p.copy(hrLostAlerted = true)
                    effects += RunEffect.PlayAlert(Alert.HrLost)
                }
                val lastFixTime = p.lastFix?.t ?: startedAt
                // Silence after a stationary fix is not GPS loss: iOS pauses updates while still (D102).
                val stillAtLastFix = p.lastFix?.stationary == true
                if (!p.gpsLost && !stillAtLastFix && t - lastFixTime >= gpsLostAfter) {
                    p = p.copy(gpsLost = true) // the screen's GPS lost; distance judges gaps itself (D113)
                    record(EventKind.GpsLost, t)
                }
            }

            b.lastPersistAt?.let { if (t - it >= persistEvery) requestPersist(t) }
        }

        /** `RunReducer.swift:91`. */
        fun heartRate(sample: HrSample) {
            if (sample.bpm !in validHeartRate) return // invalid: as if it never came (D119)
            p.lastHr?.let { if (sample.t < it.t) return } // late input: dropped
            if (p.startedAt == null || p.phase == RunPhase.Done) return
            p = p.copy(
                lastHr = sample,
                hrSum = p.hrSum + sample.bpm,
                hrCount = p.hrCount + 1,
                hrMax = maxOf(p.hrMax ?: sample.bpm, sample.bpm),
            )
            b = b.copy(hr = b.hr + sample)
            if (p.hrLost) {
                p = p.copy(hrLost = false, hrLostAlerted = false)
                record(EventKind.HrBack, sample.t)
            }
        }

        /** `RunReducer.swift:107`. */
        fun location(fix: LocationFix) {
            p.lastFix?.let { if (fix.t < it.t) return } // late input: dropped
            if (p.phase is RunPhase.Countdown) {
                // The runner stands at the start line: the latest good fix is the starting point.
                if (fix.horizontalAccuracy <= DistanceTracker.distanceAccuracy) p = p.copy(startFix = fix)
                return
            }
            if (p.startedAt == null || p.stoppedAt != null) return
            p = p.copy(lastFix = fix)
            b = b.copy(fixes = b.fixes + fix)

            if (fix.horizontalAccuracy > gpsLostAccuracy) {
                if (!p.gpsLost) {
                    p = p.copy(gpsLost = true)
                    record(EventKind.GpsLost, fix.t)
                }
            } else if (p.gpsLost) {
                p = p.copy(gpsLost = false)
                record(EventKind.GpsBack, fix.t)
            }
            count(trackDistance(fix, counting = p.phase == RunPhase.Active))
        }

        /** `RunReducer.swift:131` — distance from every fix, with the same GPS-loss thresholds as the screen (D113, D116). */
        private fun trackDistance(fix: LocationFix, counting: Boolean): DistanceTracker.CountedSegment? =
            apply(p.tracker.add(fix, counting, lostAfter = gpsLostAfter, lostAccuracy = gpsLostAccuracy))

        private fun flushLag(): DistanceTracker.CountedSegment? = apply(p.tracker.flushLag())

        /** The Swift `mutating` call: keep the tracker's next state, hand back what it counted. */
        private fun apply(step: DistanceTracker.Step): DistanceTracker.CountedSegment? {
            p = p.copy(tracker = step.tracker)
            return step.segment
        }

        /** `RunReducer.swift:135`. */
        fun sensor(state: SensorState, t: Instant) {
            if (p.startedAt == null || p.phase == RunPhase.Done) return
            when (state) {
                is SensorState.Disconnected -> if (state.reason == DisconnectReason.Lost) lost(t)
                SensorState.BluetoothOff -> lost(t)
                is SensorState.Connected -> if (p.sensorLost) {
                    p = p.copy(sensorLost = false)
                    record(EventKind.SensorReconnected, t)
                }
                SensorState.Connecting, SensorState.Unauthorized -> Unit
            }
        }

        private fun lost(t: Instant) {
            if (!p.sensorLost) {
                p = p.copy(sensorLost = true)
                record(EventKind.SensorLost, t)
            }
        }

        /** `RunReducer.swift:153` — Swift's two-subject `switch (action, phase)` as one guarded `when` (same branch order). */
        fun user(action: UserAction, t: Instant) {
            val phase = p.phase
            when {
                action == UserAction.Start && phase is RunPhase.Gate -> {
                    p = p.copy(phase = RunPhase.Countdown(endsAt = t + countdown), countdownTicksSent = 1)
                    effects += RunEffect.PlayAlert(Alert.CountdownTick)
                }
                action == UserAction.Pause && phase is RunPhase.Active -> {
                    count(flushLag())
                    p = p.copy(phase = RunPhase.Paused(since = t), currentPace = p.currentPace.clear()) // paused time never enters current pace (D106)
                    record(EventKind.Pause, t, EventSource.Manual)
                    requestPersist(t)
                }
                action == UserAction.Resume && phase is RunPhase.Paused -> {
                    closeOpenPause(phase.since, t)
                    p = p.copy(phase = RunPhase.Active, currentPace = p.currentPace.clear(), tracker = p.tracker.resume())
                    record(EventKind.Resume, t, EventSource.Manual)
                }
                action == UserAction.Stop && (phase is RunPhase.Active || phase is RunPhase.Paused) -> {
                    if (phase is RunPhase.Active) count(flushLag())
                    if (phase is RunPhase.Paused) closeOpenPause(phase.since, t)
                    p = p.copy(stoppedAt = t, phase = RunPhase.Cooldown(until = t + cooldown))
                    // Stop first: once any later batch is confirmed, the stop is durable too.
                    effects += RunEffect.Stop(t)
                    requestPersist(t)
                }
                action == UserAction.SkipCooldown && phase is RunPhase.Cooldown -> {
                    endCooldown(at = t, persistAt = t, source = EventSource.Manual)
                }
                else -> Unit // not allowed in this phase: ignored
            }
        }

        /** `RunReducer.swift:188`. */
        fun persisted(seq: Int) {
            val flight = b.inFlight ?: return
            if (flight.seq != seq) return
            b = b.copy(hr = b.hr.drop(flight.hr), fixes = b.fixes.drop(flight.fixes), events = b.events.drop(flight.events), inFlight = null)
            val requestedAt = b.flushRequestedAt
            if (requestedAt != null) {
                b = b.copy(flushRequestedAt = null)
                requestPersist(requestedAt)
            } else if (p.phase == RunPhase.Done && b.isEmpty && !b.computingRequested) {
                b = b.copy(computingRequested = true)
                effects += RunEffect.BeginComputing
            }
        }

        /** `RunReducer.swift:203`. */
        fun persistFailed(seq: Int) {
            val flight = b.inFlight ?: return
            if (flight.seq != seq) return
            b = b.copy(inFlight = null) // data stays; the next persist retries it
        }

        // ── Helpers ─────────────────────────────────────────────────────────────────────────

        /** `RunReducer.swift:210`. */
        private fun begin(t: Instant) {
            p = p.copy(phase = RunPhase.Active, startedAt = t, countdownTicksSent = 0)
            b = b.copy(lastPersistAt = t)
            p.startFix?.let { startFix ->
                // Stored like any fix, so a rebuild starts from the same point.
                p = p.copy(startFix = null, lastFix = startFix)
                trackDistance(startFix, counting = false) // the starting point
                b = b.copy(fixes = b.fixes + startFix)
            }
            effects += RunEffect.Create(
                Recording(id = config.recordingId, type = config.type, state = RecordingState.Active, startUtc = t, tzOffset = config.tzOffset),
            )
        }

        /** `RunReducer.swift:231`. */
        private fun endCooldown(at: Instant, persistAt: Instant, source: EventSource) {
            p = p.copy(phase = RunPhase.Done)
            record(EventKind.CooldownEnd, at, source)
            requestPersist(persistAt)
        }

        /** `RunReducer.swift:240` — counted GPS distance becomes route points and distance. */
        private fun count(segment: DistanceTracker.CountedSegment?) {
            segment ?: return
            segment.routePoints.forEach { effects += RunEffect.Route(it) }
            addDistance(segment.metres, from = segment.from.t, to = segment.to.t)
        }

        /**
         * `RunReducer.swift:249` — a km mark inside the segment is reached at the moment read off the
         * segment, linear in time; the next split starts there (D121). The alert plays now.
         */
        private fun addDistance(metres: Double, from: Instant, to: Instant) {
            val before = p.distance
            val total = p.distance + metres
            p = p.copy(distance = total, currentPace = p.currentPace.append(total = total, at = to))
            val span = to - from
            while (p.distance >= (p.splitIndex + 1) * splitDistance) {
                val index = p.splitIndex + 1
                val mark = index * splitDistance
                val reached = from + span * ((mark - before) / metres)
                // activeTime reads only start/stop/pause fields, none of which this copy changes.
                p = p.copy(splitIndex = index, splitStartDistance = mark, splitStartActive = p.activeTime(reached))
                record(EventKind.Split(index), reached)
                effects += RunEffect.PlayAlert(Alert.Split)
            }
        }

        /** `RunReducer.swift:268` — the time since the pause began counts as paused time (never negative). */
        private fun closeOpenPause(since: Instant, t: Instant) {
            p = p.copy(pausedTotal = p.pausedTotal + maxOf(Duration.ZERO, t - since))
        }

        /** `RunReducer.swift:272`. */
        private fun record(kind: EventKind, t: Instant, source: EventSource = EventSource.Auto) {
            b = b.copy(events = b.events + RecordingEvent(t, kind, source))
        }

        /** `RunReducer.swift:277` — persists everything buffered now, or right after the batch in flight. */
        fun requestPersist(t: Instant?) {
            t ?: return
            if (p.startedAt == null) return
            if (b.inFlight != null) {
                b = b.copy(flushRequestedAt = maxOf(t, b.flushRequestedAt ?: t))
                return
            }
            val seq = b.nextSeq
            // The heartbeat is never older than the data saved with it (ADR 0006, D117).
            val newest = listOfNotNull(p.lastTick, b.hr.lastOrNull()?.t, b.fixes.lastOrNull()?.t, b.events.maxByOrNull { it.t }?.t).maxOrNull()
            val heartbeat = maxOf(t, newest ?: t)
            b = b.copy(
                nextSeq = seq + 1,
                inFlight = PersistBuffer.InFlight(seq, b.hr.size, b.fixes.size, b.events.size),
                lastPersistAt = heartbeat,
            )
            effects += RunEffect.Persist(seq, FlushBatch(config.recordingId, b.hr, b.fixes, b.events, persistedAt = heartbeat))
        }
    }
}
