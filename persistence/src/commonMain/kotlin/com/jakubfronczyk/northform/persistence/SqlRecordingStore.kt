package com.jakubfronczyk.northform.persistence

import app.cash.sqldelight.db.SqlDriver
import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.MetricId
import com.jakubfronczyk.northform.core.MetricResult
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.UserId
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.RecordingStoreError
import com.jakubfronczyk.northform.core.ports.ProfileStore
import com.jakubfronczyk.northform.core.ports.ReliabilityStore
import com.jakubfronczyk.northform.core.ports.SavedRow
import com.jakubfronczyk.northform.core.ports.SavedRows
import com.jakubfronczyk.northform.core.reliability.ReliabilityRecord
import com.jakubfronczyk.northform.core.fromWire
import com.jakubfronczyk.northform.core.wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import com.jakubfronczyk.northform.persistence.Recording as RecordingRow

/**
 * `RecordingStore` on SQLDelight (D7/D12). Each port method is one SQLite transaction and one legal
 * step of active → stopping → computing → saved; an illegal step throws before anything is written,
 * and a throw inside the transaction rolls it back. The rules are the ones `MemoryRecords` keeps, so
 * both pass the same `RecordingStoreContract`.
 *
 * The driver is blocking and `synchronous=FULL` fsyncs every commit, so every call hops to [io]
 * (`Dispatchers.IO` on the phone; tests pass `EmptyCoroutineContext` to stay on the virtual clock's
 * thread). `withContext` is the idiom: the caller's coroutine moves to that dispatcher and comes back,
 * no second scope. The transaction never suspends, so it stays on one thread and one connection.
 */
class SqlRecordingStore(
    driver: SqlDriver,
    private val io: CoroutineContext = Dispatchers.IO,
) : RecordingStore, ReliabilityStore, SavedRows, ProfileStore {
    private val db = NorthformDb(driver)
    private val q get() = db.recordingQueries

    override suspend fun create(recording: Recording): Unit = tx {
        if (q.selectRecording(recording.id.value).executeAsOneOrNull() != null) throw RecordingStoreError.AlreadyExists(recording.id)
        if (recording.state != RecordingState.Active) throw RecordingStoreError.IllegalTransition(recording.state, RecordingState.Active)
        q.insertRecording(
            id = recording.id.value, user_id = recording.userId.value.toLong(), type = recording.type.wire, tag = recording.tag?.wire,
            state = recording.state.wire, start_utc = recording.startUtc.toNanos(), tz_offset_s = recording.tzOffset.toLong(),
            end_utc = recording.endUtc?.toNanos(), rpe = recording.rpe?.toLong(), notes = recording.notes,
        )
    }

    override suspend fun flush(batch: FlushBatch): Unit = tx {
        val row = row(batch.recordingId)
        val state = row.recordingState()
        if (state != RecordingState.Active && state != RecordingState.Stopping) throw RecordingStoreError.IllegalTransition(state, state)
        val id = batch.recordingId.value
        if (batch.hr.isNotEmpty()) {
            val t0 = batch.hr.first().t
            q.insertChunk(id, KIND_HR, id, KIND_HR, t0.toNanos(), batch.hr.size.toLong(), StreamCodec.HR, StreamCodec.encodeHr(batch.hr, t0))
        }
        if (batch.fixes.isNotEmpty()) {
            val t0 = batch.fixes.first().t
            q.insertChunk(id, KIND_GPS, id, KIND_GPS, t0.toNanos(), batch.fixes.size.toLong(), StreamCodec.GPS, StreamCodec.encodeGps(batch.fixes, t0))
        }
        batch.events.forEach { insertEvent(id, it) }
        q.setLastPersistedAt(batch.persistedAt.toNanos(), id)
    }

    override suspend fun stop(id: RecordingId, endUtc: Instant): Unit = tx {
        step(row(id), from = RecordingState.Active, to = RecordingState.Stopping)
        q.setStopping(endUtc.toNanos(), id.value)
        insertEvent(id.value, RecordingEvent(endUtc, EventKind.Stop, EventSource.Manual))
    }

    override suspend fun beginComputing(id: RecordingId): Unit = tx {
        step(row(id), from = RecordingState.Stopping, to = RecordingState.Computing)
        q.setState(RecordingState.Computing.wire, id.value)
    }

    override suspend fun saveSummary(id: RecordingId, tag: RunTag?, rpe: Int?, notes: String?, metrics: List<MetricResult>): Unit = tx {
        step(row(id), from = RecordingState.Computing, to = RecordingState.Saved)
        q.setSaved(tag?.wire, rpe?.toLong(), notes, id.value)
        q.deleteMetrics(id.value)
        for (m in metrics) {
            require(!m.value.isNaN()) { "metric ${m.metricId.value} is NaN: a metric that cannot be computed is left out, not stored" } // SQLite binds NaN as NULL
            val p = m.profile
            q.insertMetric(
                id.value, m.metricId.value, m.version.toLong(), m.value,
                p.birthDate.toNanos(), p.sex.wire, p.weightKg, p.heightCm, p.hrMax.toLong(), p.hrRest.toLong(),
                p.zones.lowerBounds.joinToString(","),
            )
        }
    }

    override suspend fun delete(id: RecordingId): Unit = tx {
        row(id)
        db.reliabilityQueries.deleteReliability(id.value)
        q.deleteMetrics(id.value)
        q.deleteChunks(id.value)
        q.deleteEvents(id.value)
        q.deleteRecording(id.value)
    }

    override suspend fun unfinished(): Recording? = tx { q.unfinished().executeAsOneOrNull()?.toRecording() }

    override suspend fun load(id: RecordingId): RecordingData? = tx { loadInTx(id) }

    /**
     * Everything in the database as one [Backup] (persistence/04, D26), read in ONE transaction so the
     * file is a consistent snapshot even while a run is being flushed. Not on the port: a backup is a
     * feature of this store, not a step of the run's state machine. The Settings export (app-ui/04)
     * calls `BackupCodec.encode(store.exportAll(clock.now()))` and writes the string to `Documents/`.
     */
    suspend fun exportAll(exportedAt: Instant): Backup = tx {
        Backup(exportedAt, q.selectRecordingIds().executeAsList().map { id -> BackupRecording(loadInTx(RecordingId(id))!!, metricsInTx(id)) })
    }

    /**
     * The reliability log row (engine/03, R29): a cache of `ReliabilityRecord.of(load(id))` so History can
     * show the counts without decoding the streams. Not on the frozen port, like [exportAll]: it is not a step of
     * the run's state machine. Upsert, any state (summary computing writes it; a rule bump recomputes it);
     * `NotFound` if the recording does not exist. Durations are stored as nanoseconds, the list as text.
     */
    override suspend fun saveReliability(id: RecordingId, record: ReliabilityRecord): Unit = tx {
        row(id)
        db.reliabilityQueries.upsertReliability(
            recording_id = id.value, version = ReliabilityRecord.version.toLong(),
            drops = record.drops.toLong(), reconnects = record.reconnects.toLong(),
            reconnect_times = record.reconnectTimes.joinToString(",") { it.inWholeNanoseconds.toString() },
            hr_lost = record.hrLost.inWholeNanoseconds, gps_lost = record.gpsLost.inWholeNanoseconds,
            crashes = record.crashes.toLong(), bluetooth_off = record.bluetoothOff.toLong(),
        )
    }

    /** The stored log, or null when none was written yet (or it was written by another rule version: recompute). */
    suspend fun reliability(id: RecordingId): ReliabilityRecord? = tx {
        db.reliabilityQueries.selectReliability(id.value).executeAsOneOrNull()
            ?.takeIf { it.version == ReliabilityRecord.version.toLong() }
            ?.let { r ->
                ReliabilityRecord(
                    drops = r.drops.toInt(), reconnects = r.reconnects.toInt(),
                    reconnectTimes = r.reconnect_times.split(',').filter { it.isNotEmpty() }.map { it.toLong().nanoseconds },
                    hrLost = r.hr_lost.nanoseconds, gpsLost = r.gps_lost.nanoseconds, crashes = r.crashes.toInt(),
                    bluetoothOff = r.bluetooth_off.toInt(),
                )
            }
    }

    /**
     * The metric rows `saveSummary` stored for one recording (engine/04's read seam, D24: each with the
     * profile it was computed with). Not on the frozen port: history screens and "recompute with the
     * stored profile" read it through `:persistence`, as `exportAll` does. Empty when none were saved.
     */
    suspend fun metrics(id: RecordingId): List<MetricResult> = tx { metricsInTx(id.value) }

    /** today-screen/01: saved recordings with their metric rows, newest start first, in one transaction. */
    override suspend fun savedNewestFirst(limit: Int): List<SavedRow> = tx {
        q.savedNewestFirst(limit.toLong()).executeAsList().map { SavedRow(it.toRecording(), metricsInTx(it.id)) }
    }

    /** profile-settings/01: the runner's current profile, or null before one is saved. */
    override suspend fun profile(): Profile? = tx {
        db.profileQueries.selectProfile().executeAsOneOrNull()?.let { p ->
            profileOf(p.birth_date, p.sex, p.weight_kg, p.height_cm, p.hr_max, p.hr_rest, p.zones)
        }
    }

    override suspend fun saveProfile(profile: Profile): Unit = tx {
        db.profileQueries.upsertProfile(
            profile.birthDate.toNanos(), profile.sex.wire, profile.weightKg, profile.heightCm,
            profile.hrMax.toLong(), profile.hrRest.toLong(), profile.zones.lowerBounds.joinToString(","),
        )
    }

    /** The inverse of `saveSummary`'s insert: the metric with the profile it was computed with (D24). */
    private fun metricsInTx(id: String): List<MetricResult> = q.selectMetrics(id).executeAsList().map { m ->
        MetricResult(
            metricId = MetricId(m.metric_id), version = m.version.toInt(), value = m.value_, // `value` is a SQL keyword: SQLDelight suffixes the property
            profile = profileOf(m.profile_birth_date, m.profile_sex, m.profile_weight_kg, m.profile_height_cm, m.profile_hr_max, m.profile_hr_rest, m.profile_zones),
        )
    }

    /** One decoding for the profile table and the metric rows' snapshot: the same columns, the same encoding. */
    private fun profileOf(birthDate: Long, sex: String, weightKg: Double, heightCm: Double, hrMax: Long, hrRest: Long, zones: String) = Profile(
        birthDate = instantOfNanos(birthDate), sex = wire(Sex.entries, sex) { it.wire },
        weightKg = weightKg, heightCm = heightCm, hrMax = hrMax.toInt(), hrRest = hrRest.toInt(),
        zones = HRZones(zones.split(',').map(String::toDouble)),
    )

    private fun loadInTx(id: RecordingId): RecordingData? {
        val row = q.selectRecording(id.value).executeAsOneOrNull() ?: return null
        return RecordingData(
            recording = row.toRecording(),
            hr = q.selectChunks(id.value, KIND_HR).executeAsList().flatMap { c ->
                check(c.codec == StreamCodec.HR) { "unknown hr codec ${c.codec}" }
                StreamCodec.decodeHr(c.payload, instantOfNanos(c.t0), c.count.toInt())
            },
            fixes = q.selectChunks(id.value, KIND_GPS).executeAsList().flatMap { c ->
                check(c.codec == StreamCodec.GPS) { "unknown gps codec ${c.codec}" }
                StreamCodec.decodeGps(c.payload, instantOfNanos(c.t0), c.count.toInt())
            },
            events = q.selectEvents(id.value).executeAsList().map { e ->
                RecordingEvent(instantOfNanos(e.t), eventKind(e.kind, e.split_index), wire(EventSource.entries, e.source) { it.wire })
            },
            lastPersistedAt = row.last_persisted_at?.let(::instantOfNanos),
        )
    }

    // ── inside a transaction ─────────────────────────────────────────────────────────────────

    private suspend fun <T> tx(body: () -> T): T = withContext(io) { db.transactionWithResult { body() } }

    private fun row(id: RecordingId): RecordingRow = q.selectRecording(id.value).executeAsOneOrNull() ?: throw RecordingStoreError.NotFound(id)

    private fun step(row: RecordingRow, from: RecordingState, to: RecordingState) {
        val state = row.recordingState()
        if (state != from) throw RecordingStoreError.IllegalTransition(state, to)
    }

    private fun insertEvent(id: String, e: RecordingEvent) =
        q.insertEvent(id, id, e.t.toNanos(), e.kind.wire, e.source.wire, (e.kind as? EventKind.Split)?.index?.toLong())

    private fun RecordingRow.recordingState() = wire(RecordingState.entries, state) { it.wire }

    private fun <E : Enum<E>> wire(entries: List<E>, value: String, wire: (E) -> String): E =
        entries.firstOrNull { wire(it) == value } ?: error("unknown ${entries.first()::class.simpleName} '$value' in the database")

    private fun RecordingRow.toRecording() = Recording(
        id = RecordingId(id),
        userId = UserId(user_id.toInt()),
        type = wire(RecordingType.entries, type) { it.wire },
        tag = tag?.let { wire(RunTag.entries, it) { t -> t.wire } },
        state = recordingState(),
        startUtc = instantOfNanos(start_utc),
        tzOffset = tz_offset_s.toInt(),
        endUtc = end_utc?.let(::instantOfNanos),
        rpe = rpe?.toInt(),
        notes = notes,
    )

    private fun eventKind(wire: String, splitIndex: Long?): EventKind =
        EventKind.fromWire(wire, splitIndex?.toInt()) ?: error("unknown event kind '$wire' (split index $splitIndex) in the database")

    private companion object {
        const val KIND_HR = "hr"
        const val KIND_GPS = "gps"
    }
}
