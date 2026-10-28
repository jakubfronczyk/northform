package com.jakubfronczyk.northform.replay

private object ResourceAnchor

/** `src/commonMain/resources` is on the JVM test classpath. */
actual fun resourceText(path: String): String =
    ResourceAnchor::class.java.getResourceAsStream("/$path")?.readBytes()?.decodeToString()
        ?: error("missing test resource /$path")

/** The JVM's shortest round-trip `Double.toString` matches Swift's `JSONEncoder`, so the corpus re-encodes byte for byte. */
actual val swiftByteParity: Boolean = true

actual fun swiftFixtureText(): String? =
    System.getenv("NORTHFORM_SWIFT_FIXTURE")?.takeIf { it.isNotBlank() }?.let { java.io.File(it).readText() }
