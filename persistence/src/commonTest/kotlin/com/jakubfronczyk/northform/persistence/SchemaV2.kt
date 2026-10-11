package com.jakubfronczyk.northform.persistence

/**
 * Schema v2 as it shipped, pinned ([PinnedSchema], recipe step 5: added when v3 arrived). v1 plus the
 * reliability table of `migrations/1.sqm`, before `2.sqm` (D31) recreated it; checked against `databases/2.db`.
 */
object SchemaV2 : PinnedSchema(
    version = 2,
    tables = SchemaV1.tables + """
        CREATE TABLE reliability (
            recording_id TEXT NOT NULL PRIMARY KEY,
            version INTEGER NOT NULL,
            drops INTEGER NOT NULL,
            reconnects INTEGER NOT NULL,
            reconnect_times TEXT NOT NULL,
            hr_lost INTEGER NOT NULL,
            gps_lost INTEGER NOT NULL,
            recoveries INTEGER NOT NULL
        )
        """.trimIndent(),
)
