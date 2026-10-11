package com.jakubfronczyk.northform.persistence

/** Schema v1 as it shipped, pinned ([PinnedSchema]); checked against `databases/1.db`. */
object SchemaV1 : PinnedSchema(
    version = 1,
    tables = listOf(
        """
        CREATE TABLE recording (
            id TEXT NOT NULL PRIMARY KEY,
            user_id INTEGER NOT NULL DEFAULT 1,
            type TEXT NOT NULL,
            tag TEXT,
            state TEXT NOT NULL CHECK (state IN ('active', 'stopping', 'computing', 'saved')),
            start_utc INTEGER NOT NULL,
            tz_offset_s INTEGER NOT NULL,
            end_utc INTEGER,
            rpe INTEGER,
            notes TEXT,
            last_persisted_at INTEGER
        )
        """,
        """
        CREATE TABLE event (
            recording_id TEXT NOT NULL,
            seq INTEGER NOT NULL,
            t INTEGER NOT NULL,
            kind TEXT NOT NULL,
            source TEXT NOT NULL,
            split_index INTEGER,
            PRIMARY KEY (recording_id, seq)
        )
        """,
        """
        CREATE TABLE stream_chunk (
            recording_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            seq INTEGER NOT NULL,
            t0 INTEGER NOT NULL,
            count INTEGER NOT NULL,
            codec TEXT NOT NULL,
            payload BLOB NOT NULL,
            PRIMARY KEY (recording_id, kind, seq)
        )
        """,
        """
        CREATE TABLE metric (
            recording_id TEXT NOT NULL,
            metric_id TEXT NOT NULL,
            version INTEGER NOT NULL,
            value REAL NOT NULL,
            profile_birth_date INTEGER NOT NULL,
            profile_sex TEXT NOT NULL,
            profile_weight_kg REAL NOT NULL,
            profile_height_cm REAL NOT NULL,
            profile_hr_max INTEGER NOT NULL,
            profile_hr_rest INTEGER NOT NULL,
            profile_zones TEXT NOT NULL,
            PRIMARY KEY (recording_id, metric_id)
        )
        """,
    ).map { it.trimIndent() },
)
