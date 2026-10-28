package com.jakubfronczyk.northform.replay

/** The bundled fixture `<name>.jsonl` / `<name>.expected.json` as text. Platform-specific: resources have no common API. */
expect fun resourceText(path: String): String

private val parsed = HashMap<String, ReplayScript>()

/** Parsed once per name: scripts are immutable, and the real recordings are read by many tests. */
fun bundled(name: String): ReplayScript = parsed.getOrPut(name) { Fixture.parse(resourceText("fixtures/$name.jsonl")) }

fun bundledExpected(name: String): String = resourceText("fixtures/$name.expected.json")

/** Whether [Fixture.encode] reproduces the Swift-written bytes on this platform, or only the same decoded values. */
expect val swiftByteParity: Boolean

/** A Swift-written fixture outside the repo (`NORTHFORM_SWIFT_FIXTURE`), or null when none is given. JVM only. */
expect fun swiftFixtureText(): String?
