package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.core.roundedHalfAwayFromZero
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Reads and writes replay fixtures (`Replay/Fixture.swift`), format v1 — frozen in the Swift build,
 * read-compatible here.
 *
 * One JSON object per line, `t` = seconds from start, `k` = kind: `meta` (first line only) · `hr` ·
 * `gps` · `sensor` · `user`. Ticks are not stored. `gps` may carry `still` and the four accuracies
 * (`sAcc`, `course`, `cAcc`, `vAcc`), all optional and omitted when unknown. `t` never goes backwards.
 * An unknown kind is an error, so typos are caught.
 *
 * WRITE parity: [encode] matches the Swift writer's sorted keys and millisecond offsets. Numbers are
 * Kotlin's shortest round-trip form with integral values printed as integers, which equals Swift's
 * `JSONEncoder` output for the bundled corpus but is not guaranteed byte-identical in general
 * (v1.0 only reads fixtures).
 */
object Fixture {
    const val version = 1

    /** Start used when a hand-written fixture has no `start`: 2026-01-01T00:00:00Z. */
    val defaultStart: Instant = Instant.fromEpochSeconds(1_767_225_600)

    sealed class LoadError(message: String) : Exception(message) {
        data object Empty : LoadError("empty")
        data object MissingMeta : LoadError("missing meta")
        data class UnsupportedVersion(val version: Int) : LoadError("unsupported version $version")
        /** 1-based line number, counting blank lines, and what is wrong with it. */
        data class BadLine(val line: Int, val reason: String) : LoadError("line $line: $reason")
    }

    /** `Fixture.swift:27`. */
    fun parse(jsonl: String): ReplayScript {
        var meta: ReplayMeta? = null
        val inputs = ArrayList<ReplayInput>()
        var lastT = 0.0

        for ((index, raw) in jsonl.split('\n').withIndex()) {
            val number = index + 1
            val text = raw.trim()
            if (text.isEmpty()) continue

            val wire = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw LoadError.BadLine(number, e.message?.lineSequence()?.first() ?: "not a JSON object")
            }
            val t = wire.double("t") ?: throw LoadError.BadLine(number, "missing t")
            val k = wire.string("k") ?: throw LoadError.BadLine(number, "missing k")

            if (k == "meta") {
                if (meta != null || inputs.isNotEmpty()) throw LoadError.BadLine(number, "meta must be the first line, and only once")
                val v = wire.int("v") ?: throw LoadError.BadLine(number, "meta needs v")
                if (v != version) throw LoadError.UnsupportedVersion(v)
                meta = wire.meta(number)
                continue
            }
            val start = meta?.start ?: throw LoadError.MissingMeta
            if (t < lastT) throw LoadError.BadLine(number, "t goes backwards")
            lastT = t
            inputs += wire.input(k, start, t, number)
        }
        // No meta here means no line at all: a data line before meta already threw MissingMeta above.
        return ReplayScript(meta ?: throw LoadError.Empty, inputs)
    }

    /** `Fixture.swift:67` — JSONL with sorted keys (stable diffs) and an ISO 8601 `start`; offsets rounded to the millisecond. */
    fun encode(script: ReplayScript): String {
        val start = script.meta.start
        val lines = listOf(metaLine(script.meta)) + script.inputs.map { inputLine(it, start) }
        return lines.joinToString("\n") { Json.encodeToString(JsonObject.serializer(), it) } + "\n"
    }

    // ── wire → core ─────────────────────────────────────────────────────────────────────────

    private fun JsonObject.meta(line: Int): ReplayMeta {
        val type = string("type")?.let { RecordingType.fromWire(it) } ?: throw LoadError.BadLine(line, "meta needs type")
        val profile = this["profile"] as? JsonObject ?: throw LoadError.BadLine(line, "meta needs profile")
        val hrMax = profile.int("hrMax") ?: throw LoadError.BadLine(line, "meta needs profile")
        val hrRest = profile.int("hrRest") ?: throw LoadError.BadLine(line, "meta needs profile")
        val sex = profile.string("sex")?.let { Sex.fromWire(it) } ?: throw LoadError.BadLine(line, "meta needs profile")
        try {
            Profile.checkHeartRates(max = hrMax, rest = hrRest)
        } catch (e: Profile.Invalid) {
            throw LoadError.BadLine(line, "invalid profile: ${e.message}")
        }
        val start = string("start")?.let { Instant.parse(it) } ?: defaultStart
        return ReplayMeta(type, start, hrMax, hrRest, sex)
    }

    private fun JsonObject.input(k: String, start: Instant, t: Double, line: Int): ReplayInput {
        val at = start + t.seconds
        return when (k) {
            "hr" -> ReplayInput.Hr(HrSample(at, int("bpm") ?: throw LoadError.BadLine(line, "hr needs bpm")))
            "gps" -> {
                val lat = double("lat")
                val lon = double("lon")
                val hAcc = double("hAcc")
                if (lat == null || lon == null || hAcc == null) throw LoadError.BadLine(line, "gps needs lat, lon and hAcc")
                ReplayInput.Fix(
                    LocationFix(
                        t = at, latitude = lat, longitude = lon, horizontalAccuracy = hAcc,
                        altitude = double("alt"), speed = double("speed"), stationary = bool("still"),
                        speedAccuracy = double("sAcc"), course = double("course"),
                        courseAccuracy = double("cAcc"), verticalAccuracy = double("vAcc"),
                    ),
                )
            }
            "sensor" -> {
                val state = when (string("state") ?: throw LoadError.BadLine(line, "sensor needs state")) {
                    "connecting" -> SensorState.Connecting
                    "connected" -> SensorState.Connected(int("battery"))
                    "lost" -> SensorState.Disconnected(DisconnectReason.Lost)
                    "userDisconnected" -> SensorState.Disconnected(DisconnectReason.User)
                    "bluetoothOff" -> SensorState.BluetoothOff
                    "unauthorized" -> SensorState.Unauthorized
                    else -> throw LoadError.BadLine(line, "sensor needs state")
                }
                ReplayInput.Sensor(state, at)
            }
            "user" -> ReplayInput.User(string("a")?.let { UserAction.fromWire(it) } ?: throw LoadError.BadLine(line, "user needs a"), at)
            else -> throw LoadError.BadLine(line, "unknown kind '$k'")
        }
    }

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    // ── core → wire ─────────────────────────────────────────────────────────────────────────

    private fun metaLine(meta: ReplayMeta): JsonObject = sorted(
        "t" to num(0.0), "k" to JsonPrimitive("meta"), "v" to JsonPrimitive(version),
        "type" to JsonPrimitive(meta.type.wire),
        "profile" to sorted("hrMax" to JsonPrimitive(meta.hrMax), "hrRest" to JsonPrimitive(meta.hrRest), "sex" to JsonPrimitive(meta.sex.wire)),
        "start" to JsonPrimitive(meta.start.toString()),
    )

    private fun inputLine(input: ReplayInput, start: Instant): JsonObject {
        val offset = ((input.t - start).inSecondsDouble * 1000).roundedHalfAwayFromZero() / 1000 // Swift `.rounded()`
        val fields = mutableListOf<Pair<String, JsonElement>>("t" to num(offset))
        when (input) {
            is ReplayInput.Hr -> fields += listOf("k" to JsonPrimitive("hr"), "bpm" to JsonPrimitive(input.sample.bpm))
            is ReplayInput.Fix -> {
                val f = input.fix
                fields += "k" to JsonPrimitive("gps")
                fields += listOf("lat" to num(f.latitude), "lon" to num(f.longitude), "hAcc" to num(f.horizontalAccuracy))
                f.altitude?.let { fields += "alt" to num(it) }
                f.speed?.let { fields += "speed" to num(it) }
                f.stationary?.let { fields += "still" to JsonPrimitive(it) }
                f.speedAccuracy?.let { fields += "sAcc" to num(it) }
                f.course?.let { fields += "course" to num(it) }
                f.courseAccuracy?.let { fields += "cAcc" to num(it) }
                f.verticalAccuracy?.let { fields += "vAcc" to num(it) }
            }
            is ReplayInput.Sensor -> {
                fields += "k" to JsonPrimitive("sensor")
                val (state, battery) = when (val s = input.state) {
                    SensorState.Connecting -> "connecting" to null
                    is SensorState.Connected -> "connected" to s.battery
                    is SensorState.Disconnected -> (if (s.reason == DisconnectReason.Lost) "lost" else "userDisconnected") to null
                    SensorState.BluetoothOff -> "bluetoothOff" to null
                    SensorState.Unauthorized -> "unauthorized" to null
                }
                fields += "state" to JsonPrimitive(state)
                battery?.let { fields += "battery" to JsonPrimitive(it) }
            }
            is ReplayInput.User -> fields += listOf("k" to JsonPrimitive("user"), "a" to JsonPrimitive(input.action.wire))
        }
        return sorted(*fields.toTypedArray())
    }

    /** Swift's JSONEncoder prints an integral Double as an integer ("t":0, "hAcc":5); mirror that. */
    private fun num(value: Double): JsonPrimitive =
        if (value == floor(value) && abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    private fun sorted(vararg fields: Pair<String, JsonElement>): JsonObject =
        JsonObject(fields.sortedBy { it.first }.associateTo(LinkedHashMap()) { it })
}
