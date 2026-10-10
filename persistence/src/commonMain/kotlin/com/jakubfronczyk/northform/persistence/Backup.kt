package com.jakubfronczyk.northform.persistence

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
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
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.core.wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

// Persistence/04, D26 (proposed): the backup file. One JSON document, format `northform-backup` v1.
// Pure functions over what the database holds; the Files-app writing is app-ui/04 and lives in the app.

/** One recording as the database knows it: the port's [RecordingData] plus the summary metrics (write-only through the port, D24). */
data class BackupRecording(val data: RecordingData, val metrics: List<MetricResult>)

/** Everything the database holds, in `startUtc` order, in any state (an unfinished run is backed up too). */
data class Backup(val exportedAt: Instant, val recordings: List<BackupRecording>)

sealed class BackupError(message: String) : Exception(message) {
    /** The `format` field is missing or names another file: a GPX, a fixture, anything. */
    data class NotABackup(val format: String?) : BackupError("not a northform backup (format = $format)")
    /** Written by a newer app. Older versions are migrated here, newer ones are refused. */
    data class UnsupportedVersion(val version: Int) : BackupError("unsupported backup version $version")
    /** Where in the document ("recordings[2].gps[7]") and what is wrong. */
    data class Malformed(val path: String, val reason: String) : BackupError("$path: $reason")
}

/**
 * `northform-backup` v1. Decided over a copy of the `.db` file (unreadable, binds the backup to the
 * schema, needs a WAL checkpoint) and over GPX/FIT per activity (GPX drops events, metrics and HR
 * precision; FIT is a binary SDK format) — those are an *export* feature for later; this is the backup.
 *
 * Layout: a header (`format`, `version`, `exportedAt`, a legend for the stream columns), then one object
 * per recording with its fields, events and metrics as named JSON (ISO-8601 UTC times, nanosecond
 * precision), and the raw streams as positional rows — `hr: [dtNs, bpm]`, `gps: [dtNs, lat, lon, hAcc,
 * alt, speed, stationary, sAcc, course, cAcc, vAcc]` with `dtNs` nanoseconds since `startUtc` and `null`
 * for an absent optional. One row per line, so a 1 h run is ~400 KB that `jq`, a spreadsheet or `grep`
 * can read; named fields on every sample would triple that for no information.
 *
 * Round trip is bit for bit: integers are JSON integers, doubles are written with Kotlin's shortest
 * round-trip `toString` and read back with `toDouble`; `NaN`/`Infinity` (never produced by the adapters,
 * but representable in a chunk) are written as bare literals, which the reader accepts. Proven on the
 * JVM and the simulator by `BackupCodecTest`.
 */
object BackupCodec {
    const val FORMAT = "northform-backup"
    const val VERSION = 1

    private val HR_COLUMNS = listOf("dtNs", "bpm")
    private val GPS_COLUMNS = listOf("dtNs", "lat", "lon", "hAcc", "alt", "speed", "stationary", "sAcc", "course", "cAcc", "vAcc")

    fun encode(backup: Backup): String = JsonLayout.render(
        obj(
            "format" to str(FORMAT),
            "version" to num(VERSION.toLong()),
            "exportedAt" to str(backup.exportedAt.toString()),
            "times" to str("ISO-8601 UTC; stream rows carry dtNs = nanoseconds since the recording's startUtc"),
            "streams" to obj("hr" to strs(HR_COLUMNS), "gps" to strs(GPS_COLUMNS)),
            "recordings" to JsonArray(backup.recordings.map { it.toJson() }),
        ),
    )

    fun decode(text: String): Backup {
        val root = runCatching { parser.parseToJsonElement(text) }.getOrElse { throw BackupError.Malformed("", "not JSON: ${it.message}") } as? JsonObject
            ?: throw BackupError.Malformed("", "the document is not an object")
        val format = root.string("format")
        if (format != FORMAT) throw BackupError.NotABackup(format)
        val version = root.int("version") ?: throw BackupError.Malformed("version", "missing")
        if (version != VERSION) throw BackupError.UnsupportedVersion(version)
        return Backup(
            exportedAt = root.instant("exportedAt", "exportedAt"),
            recordings = root.array("recordings", "recordings").mapIndexed { i, e -> e.obj("recordings[$i]").toRecording("recordings[$i]") },
        )
    }

    /** `allowSpecialFloatingPointValues` reads the bare `NaN`/`Infinity` literals the writer may emit. */
    private val parser = Json { allowSpecialFloatingPointValues = true }

    // ── core → JSON ─────────────────────────────────────────────────────────────────────────

    private fun BackupRecording.toJson(): JsonObject {
        val r = data.recording
        val start = r.startUtc.toNanos()
        return obj(
            "id" to str(r.id.value),
            "userId" to num(r.userId.value.toLong()),
            "type" to str(r.type.wire),
            "tag" to (r.tag?.let { str(it.wire) } ?: JsonNull),
            "state" to str(r.state.wire),
            "startUtc" to str(r.startUtc.toString()),
            "tzOffsetS" to num(r.tzOffset.toLong()),
            "endUtc" to (r.endUtc?.let { str(it.toString()) } ?: JsonNull),
            "rpe" to (r.rpe?.let { num(it.toLong()) } ?: JsonNull),
            "notes" to (r.notes?.let { str(it) } ?: JsonNull),
            "lastPersistedAt" to (data.lastPersistedAt?.let { str(it.toString()) } ?: JsonNull),
            "events" to JsonArray(data.events.map { e ->
                obj(
                    "t" to str(e.t.toString()), "kind" to str(e.kind.wire), "source" to str(e.source.wire),
                    "splitIndex" to ((e.kind as? EventKind.Split)?.let { num(it.index.toLong()) } ?: JsonNull),
                )
            }),
            "metrics" to JsonArray(metrics.map { m ->
                obj("id" to str(m.metricId.value), "version" to num(m.version.toLong()), "value" to dbl(m.value), "profile" to m.profile.toJson())
            }),
            "hr" to JsonArray(data.hr.map { s -> JsonArray(listOf(num(s.t.toNanos() - start), num(s.bpm.toLong()))) }),
            "gps" to JsonArray(data.fixes.map { f ->
                JsonArray(
                    listOf(
                        num(f.t.toNanos() - start), dbl(f.latitude), dbl(f.longitude), dbl(f.horizontalAccuracy),
                        f.altitude.orNull(), f.speed.orNull(), f.stationary?.let { JsonPrimitive(it) } ?: JsonNull,
                        f.speedAccuracy.orNull(), f.course.orNull(), f.courseAccuracy.orNull(), f.verticalAccuracy.orNull(),
                    ),
                )
            }),
        )
    }

    private fun Profile.toJson() = obj(
        "birthDate" to str(birthDate.toString()), "sex" to str(sex.wire), "weightKg" to dbl(weightKg), "heightCm" to dbl(heightCm),
        "hrMax" to num(hrMax.toLong()), "hrRest" to num(hrRest.toLong()), "zones" to JsonArray(zones.lowerBounds.map(::dbl)),
    )

    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*fields))
    private fun str(v: String) = JsonPrimitive(v)
    private fun strs(v: List<String>) = JsonArray(v.map(::str))
    private fun num(v: Long) = JsonPrimitive(v)
    private fun dbl(v: Double) = JsonPrimitive(v)
    private fun Double?.orNull(): JsonElement = this?.let(::dbl) ?: JsonNull

    // ── JSON → core ─────────────────────────────────────────────────────────────────────────

    private fun JsonObject.toRecording(path: String): BackupRecording {
        val start = instant("startUtc", "$path.startUtc")
        val recording = Recording(
            id = RecordingId(string("id") ?: throw BackupError.Malformed("$path.id", "missing")),
            userId = UserId(int("userId") ?: throw BackupError.Malformed("$path.userId", "missing")),
            type = enum("type", RecordingType.entries, "$path.type") { it.wire },
            tag = (this["tag"] as? JsonPrimitive)?.takeIf { it.isString }?.let { wire -> RunTag.entries.firstOrNull { it.wire == wire.content } ?: throw BackupError.Malformed("$path.tag", "unknown tag ${wire.content}") },
            state = enum("state", RecordingState.entries, "$path.state") { it.wire },
            startUtc = start,
            tzOffset = int("tzOffsetS") ?: throw BackupError.Malformed("$path.tzOffsetS", "missing"),
            endUtc = optionalInstant("endUtc", "$path.endUtc"),
            rpe = int("rpe"),
            notes = string("notes"),
        )
        val base = start.toNanos()
        return BackupRecording(
            data = RecordingData(
                recording = recording,
                hr = array("hr", "$path.hr").mapIndexed { i, row -> row.row("$path.hr[$i]", HR_COLUMNS.size).let { HrSample(instantOfNanos(base + it.long(0)), it.long(1).toInt()) } },
                fixes = array("gps", "$path.gps").mapIndexed { i, row ->
                    val c = row.row("$path.gps[$i]", GPS_COLUMNS.size)
                    LocationFix(
                        t = instantOfNanos(base + c.long(0)), latitude = c.double(1), longitude = c.double(2), horizontalAccuracy = c.double(3),
                        altitude = c.optionalDouble(4), speed = c.optionalDouble(5), stationary = c.optionalBoolean(6),
                        speedAccuracy = c.optionalDouble(7), course = c.optionalDouble(8), courseAccuracy = c.optionalDouble(9), verticalAccuracy = c.optionalDouble(10),
                    )
                },
                events = array("events", "$path.events").mapIndexed { i, e ->
                    val o = e.obj("$path.events[$i]")
                    RecordingEvent(o.instant("t", "$path.events[$i].t"), o.eventKind("$path.events[$i]"), o.enum("source", EventSource.entries, "$path.events[$i].source") { it.wire })
                },
                lastPersistedAt = optionalInstant("lastPersistedAt", "$path.lastPersistedAt"),
            ),
            metrics = array("metrics", "$path.metrics").mapIndexed { i, e ->
                val o = e.obj("$path.metrics[$i]")
                val p = o["profile"]?.obj("$path.metrics[$i].profile") ?: throw BackupError.Malformed("$path.metrics[$i].profile", "missing")
                MetricResult(
                    metricId = MetricId(o.string("id") ?: throw BackupError.Malformed("$path.metrics[$i].id", "missing")),
                    version = o.int("version") ?: throw BackupError.Malformed("$path.metrics[$i].version", "missing"),
                    value = o.double("value") ?: throw BackupError.Malformed("$path.metrics[$i].value", "missing"),
                    profile = runCatching {
                        Profile(
                            birthDate = p.instant("birthDate", "$path.metrics[$i].profile.birthDate"),
                            sex = p.enum("sex", Sex.entries, "$path.metrics[$i].profile.sex") { it.wire },
                            weightKg = p.double("weightKg") ?: throw BackupError.Malformed("$path.metrics[$i].profile.weightKg", "missing"),
                            heightCm = p.double("heightCm") ?: throw BackupError.Malformed("$path.metrics[$i].profile.heightCm", "missing"),
                            hrMax = p.int("hrMax") ?: throw BackupError.Malformed("$path.metrics[$i].profile.hrMax", "missing"),
                            hrRest = p.int("hrRest") ?: throw BackupError.Malformed("$path.metrics[$i].profile.hrRest", "missing"),
                            zones = HRZones(p.array("zones", "$path.metrics[$i].profile.zones").mapIndexed { z, v -> v.primitive("$path.metrics[$i].profile.zones[$z]").doubleOrNull ?: throw BackupError.Malformed("$path.metrics[$i].profile.zones[$z]", "not a number") }),
                        )
                    }.getOrElse { if (it is BackupError) throw it else throw BackupError.Malformed("$path.metrics[$i].profile", it.message ?: "invalid") },
                )
            },
        )
    }

    private fun JsonObject.eventKind(path: String): EventKind {
        val kind = string("kind") ?: throw BackupError.Malformed("$path.kind", "missing")
        if (kind == "split") return EventKind.Split(int("splitIndex") ?: throw BackupError.Malformed("$path.splitIndex", "a split needs its index"))
        return listOf(
            EventKind.Pause, EventKind.Resume, EventKind.HrLost, EventKind.HrBack, EventKind.GpsLost, EventKind.GpsBack,
            EventKind.SensorLost, EventKind.SensorReconnected, EventKind.Stop, EventKind.CooldownEnd, EventKind.CrashRecovered,
        ).firstOrNull { it.wire == kind } ?: throw BackupError.Malformed("$path.kind", "unknown event kind $kind")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    private fun JsonObject.array(key: String, path: String): JsonArray = this[key] as? JsonArray ?: throw BackupError.Malformed(path, "missing or not an array")
    private fun JsonObject.instant(key: String, path: String): Instant = optionalInstant(key, path) ?: throw BackupError.Malformed(path, "missing")
    private fun JsonObject.optionalInstant(key: String, path: String): Instant? =
        string(key)?.let { runCatching { Instant.parse(it) }.getOrElse { e -> throw BackupError.Malformed(path, "not an ISO-8601 instant: $it") } }
    private fun <E : Enum<E>> JsonObject.enum(key: String, entries: List<E>, path: String, wire: (E) -> String): E {
        val v = string(key) ?: throw BackupError.Malformed(path, "missing")
        return entries.firstOrNull { wire(it) == v } ?: throw BackupError.Malformed(path, "unknown value $v")
    }
    private fun JsonElement.obj(path: String): JsonObject = this as? JsonObject ?: throw BackupError.Malformed(path, "not an object")
    private fun JsonElement.primitive(path: String): JsonPrimitive = this as? JsonPrimitive ?: throw BackupError.Malformed(path, "not a value")

    /** One positional stream row, checked for width once so the column reads can stay plain. */
    private fun JsonElement.row(path: String, width: Int): Row {
        val a = this as? JsonArray ?: throw BackupError.Malformed(path, "not a row")
        if (a.size != width) throw BackupError.Malformed(path, "$width columns expected, ${a.size} found")
        return Row(a, path)
    }

    private class Row(private val a: JsonArray, private val path: String) {
        private fun prim(i: Int) = a[i] as? JsonPrimitive ?: throw BackupError.Malformed("$path[$i]", "not a value")
        fun long(i: Int): Long = prim(i).longOrNull ?: throw BackupError.Malformed("$path[$i]", "not an integer")
        fun double(i: Int): Double = prim(i).doubleOrNull ?: throw BackupError.Malformed("$path[$i]", "not a number")
        fun optionalDouble(i: Int): Double? = if (a[i] is JsonNull) null else double(i)
        fun optionalBoolean(i: Int): Boolean? = if (a[i] is JsonNull) null else prim(i).booleanOrNull ?: throw BackupError.Malformed("$path[$i]", "not a boolean")
    }
}

/**
 * The writer's layout: objects one field per line, a list of rows one row per line, a row of plain
 * values on one line. kotlinx's `prettyPrint` puts every number on its own line (eleven lines per GPS
 * fix); its compact mode puts a whole run on one. Values themselves are kotlinx's (`JsonPrimitive.
 * toString()` escapes strings and prints numbers), so only the whitespace is ours.
 */
internal object JsonLayout {
    fun render(root: JsonElement): String = buildString { write(root, 0); append('\n') }

    private fun StringBuilder.write(e: JsonElement, depth: Int) {
        when (e) {
            is JsonObject -> if (e.isEmpty()) append("{}") else {
                append("{\n")
                e.entries.forEachIndexed { i, (k, v) ->
                    indent(depth + 1); append(JsonPrimitive(k).toString()); append(": "); write(v, depth + 1)
                    append(if (i < e.size - 1) ",\n" else "\n")
                }
                indent(depth); append('}')
            }
            is JsonArray -> when {
                e.isEmpty() -> append("[]")
                e.all { it is JsonPrimitive } -> append(e.joinToString(",", "[", "]") { it.toString() })
                else -> {
                    append("[\n")
                    e.forEachIndexed { i, v -> indent(depth + 1); write(v, depth + 1); append(if (i < e.size - 1) ",\n" else "\n") }
                    indent(depth); append(']')
                }
            }
            is JsonPrimitive -> append(e.toString())
        }
    }

    private fun StringBuilder.indent(depth: Int) = repeat(depth) { append("  ") }
}
