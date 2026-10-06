package com.jakubfronczyk.northform.replay

private object ResourceAnchor

/** `src/commonMain/resources` is on the JVM test classpath. */
actual fun resourceText(path: String): String =
    ResourceAnchor::class.java.getResourceAsStream("/$path")?.readBytes()?.decodeToString()
        ?: error("missing test resource /$path")
