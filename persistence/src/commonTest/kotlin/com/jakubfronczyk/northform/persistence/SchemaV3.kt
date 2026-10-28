package com.jakubfronczyk.northform.persistence

/**
 * Schema v3 as it shipped, pinned ([PinnedSchema], recipe step 5: added when v4 arrived). v1 plus the reliability
 * table as `migrations/2.sqm` recreated it (D31, D32), before `3.sqm` added the profile; checked against `databases/3.db`.
 */
object SchemaV3 : PinnedSchema(
    version = 3,
    tables = SchemaV1.tables + """
        CREATE TABLE reliability (
            recording_id TEXT NOT NULL PRIMARY KEY,
            version INTEGER NOT NULL,
            drops INTEGER NOT NULL,
            reconnects INTEGER NOT NULL,
            reconnect_times TEXT NOT NULL,
            hr_lost INTEGER NOT NULL,
            gps_lost INTEGER NOT NULL,
            crashes INTEGER NOT NULL,
            bluetooth_off INTEGER NOT NULL
        )
        """.trimIndent(),
)
