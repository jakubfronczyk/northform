package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.UserAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** `Tests/ReplayTests/FixtureTests.swift`. If a test here changes, the frozen format changed. */
class FixtureTest {

    private val example = """
        {"t":0.0,"k":"meta","v":1,"type":"run","profile":{"hrMax":187,"hrRest":60,"sex":"m"}}
        {"t":0.4,"k":"hr","bpm":131}
        {"t":1.0,"k":"gps","lat":52.5163,"lon":13.3777,"hAcc":4.8,"alt":34.2,"speed":2.9}
        {"t":612.0,"k":"sensor","state":"lost"}
        {"t":640.0,"k":"user","a":"pause"}
    """.trimIndent()

    @Test
    fun parses_frozen_v1_example_into_core_types() {
        val script = Fixture.parse(example)
        val start = Fixture.defaultStart
        assertEquals(ReplayMeta(RecordingType.Run, start, 187, 60, Sex.Male), script.meta)
        assertEquals(
            listOf(
                ReplayInput.Hr(HrSample(start + 0.4.seconds, 131)),
                ReplayInput.Fix(LocationFix(start + 1.seconds, 52.5163, 13.3777, 4.8, altitude = 34.2, speed = 2.9)),
                ReplayInput.Sensor(SensorState.Disconnected(DisconnectReason.Lost), start + 612.seconds),
                ReplayInput.User(UserAction.Pause, start + 640.seconds),
            ),
            script.inputs,
        )
    }

    @Test
    fun writer_and_parser_agree() {
        val start = Instant.fromEpochSeconds(1_790_000_000)
        val script = ReplayScript(
            ReplayMeta(RecordingType.Walk, start, 187, 58, Sex.Female),
            listOf(
                ReplayInput.Hr(HrSample(start + 0.5.seconds, 96)),
                ReplayInput.Fix(LocationFix(start + 1.seconds, 52.5, 13.4, 6.0)),
                ReplayInput.Fix(LocationFix(start + 1.2.seconds, 52.5, 13.4, 6.0, stationary = true)),
                ReplayInput.Sensor(SensorState.Connected(80), start + 1.5.seconds),
                ReplayInput.Sensor(SensorState.Disconnected(DisconnectReason.User), start + 2.seconds),
                ReplayInput.Sensor(SensorState.BluetoothOff, start + 2.5.seconds),
                ReplayInput.User(UserAction.SkipCooldown, start + 3.seconds),
            ),
        )
        val text = Fixture.encode(script)
        assertTrue(text.startsWith("""{"k":"meta","profile":"""), text.lineSequence().first())
        assertTrue(text.contains(""""start":"2026-09-21T"""))
        assertTrue(text.contains(""""still":true"""), "the optional still field is written")
        assertEquals(2, text.split("\"still\"").size, "and omitted when unknown")
        assertEquals(script, Fixture.parse(text))
    }

    @Test
    fun meta_start_is_whole_seconds() {
        val meta = ReplayMeta(RecordingType.Run, Instant.fromEpochMilliseconds(100_700), 187, 60, Sex.Male)
        assertEquals(Instant.fromEpochSeconds(100), meta.start)
    }

    private val meta = """{"t":0,"k":"meta","v":1,"type":"run","profile":{"hrMax":187,"hrRest":60,"sex":"m"}}"""

    @Test
    fun rejects_bad_fixtures() {
        val cases: List<Pair<String, Fixture.LoadError>> = listOf(
            "" to Fixture.LoadError.Empty,
            "\n\n" to Fixture.LoadError.Empty,
            """{"t":0,"k":"hr","bpm":120}""" to Fixture.LoadError.MissingMeta,
            """{"t":0,"k":"meta","v":2,"type":"run","profile":{"hrMax":187,"hrRest":60,"sex":"m"}}""" to Fixture.LoadError.UnsupportedVersion(2),
            "$meta\n\n{\"t\":1,\"k\":\"hrr\",\"bpm\":120}" to Fixture.LoadError.BadLine(3, "unknown kind 'hrr'"),
            "$meta\n{\"t\":1,\"k\":\"hr\"}" to Fixture.LoadError.BadLine(2, "hr needs bpm"),
            "$meta\n{\"k\":\"hr\",\"bpm\":1}" to Fixture.LoadError.BadLine(2, "missing t"),
            "$meta\n$meta" to Fixture.LoadError.BadLine(2, "meta must be the first line, and only once"),
            """{"t":0,"k":"meta","v":1,"type":"run","profile":{"hrMax":60,"hrRest":60,"sex":"m"}}""" to Fixture.LoadError.BadLine(1, "invalid profile: hrMax(60)"),
            """{"t":0,"k":"meta","v":1,"type":"run","profile":{"hrMax":187,"hrRest":20,"sex":"m"}}""" to Fixture.LoadError.BadLine(1, "invalid profile: hrRest(20)"),
            "$meta\n{\"t\":5,\"k\":\"hr\",\"bpm\":120}\n{\"t\":4,\"k\":\"hr\",\"bpm\":121}" to Fixture.LoadError.BadLine(3, "t goes backwards"),
            "$meta\n{\"t\":-1,\"k\":\"hr\",\"bpm\":120}" to Fixture.LoadError.BadLine(2, "t goes backwards"),
        )
        for ((input, expected) in cases) {
            val error = assertFailsWith<Fixture.LoadError>(input) { Fixture.parse(input) }
            assertEquals(expected, error, input)
        }
    }

    private val d114Meta = ReplayMeta(RecordingType.Run, Instant.fromEpochSeconds(1_790_000_000), 187, 60, Sex.Male)

    @Test
    fun gps_accuracy_fields_round_trip() {
        val full = LocationFix(
            d114Meta.start + 1.seconds, 52.5, 13.4, 5.0,
            altitude = 40.0, speed = 2.8, stationary = false,
            speedAccuracy = 0.4, course = 271.5, courseAccuracy = 12.0, verticalAccuracy = 3.5,
        )
        val script = ReplayScript(d114Meta, listOf(ReplayInput.Fix(full)))
        val jsonl = Fixture.encode(script)
        assertTrue(jsonl.contains(""""sAcc":0.4""") && jsonl.contains(""""course":271.5""") && jsonl.contains(""""cAcc":12""") && jsonl.contains(""""vAcc":3.5"""), jsonl)
        assertEquals(script, Fixture.parse(jsonl))
    }

    @Test
    fun gps_accuracy_fields_are_omitted_when_unknown() {
        val bare = LocationFix(d114Meta.start + 1.seconds, 52.5, 13.4, 5.0)
        val script = ReplayScript(d114Meta, listOf(ReplayInput.Fix(bare)))
        val jsonl = Fixture.encode(script)
        for (key in listOf("sAcc", "course", "cAcc", "vAcc")) assertTrue(!jsonl.contains("\"$key\""), "$key absent")
        assertEquals(script, Fixture.parse(jsonl))
    }

    /** Every bundled fixture parses, and writing it back gives the same inputs. They predate D114, so the new fields are null. */
    @Test
    fun bundled_fixtures_parse_and_round_trip_unchanged() {
        val names = listOf("run-1-park", "run-2-walk", "sitting-still-10min", "slow-walk-5min", "straight-run-3min")
        for (name in names) {
            val text = resourceText("fixtures/$name.jsonl")
            val script = Fixture.parse(text)
            assertEquals(script, Fixture.parse(Fixture.encode(script)), name)
            val fixes = script.inputs.filterIsInstance<ReplayInput.Fix>().map { it.fix }
            assertTrue(fixes.all { it.speedAccuracy == null && it.course == null && it.courseAccuracy == null && it.verticalAccuracy == null }, name)
        }
    }

    /** Not required for v1.0 (read-only), but worth knowing: the Kotlin writer reproduces the Swift bytes for the corpus. */
    @Test
    fun bundled_fixtures_re_encode_byte_identical() {
        val differing = listOf("run-1-park", "run-2-walk", "sitting-still-10min", "slow-walk-5min", "straight-run-3min").filter { name ->
            val text = resourceText("fixtures/$name.jsonl")
            Fixture.encode(Fixture.parse(text)) != text
        }
        assertEquals(emptyList(), differing, "fixtures whose re-encoding differs from the Swift-written bytes")
    }
}
