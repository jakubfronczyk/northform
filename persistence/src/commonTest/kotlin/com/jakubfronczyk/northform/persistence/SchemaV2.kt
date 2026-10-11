package com.jakubfronczyk.northform.persistence

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Schema v2 as it shipped, pinned (persistence/03 recipe, step 5: added when v3 arrived). v1 plus the
 * reliability table of `migrations/1.sqm`, before `2.sqm` (D31) recreated it. The JVM test checks it
 * against the committed snapshot `databases/2.db` column for column, like [SchemaV1].
 */
object SchemaV2 : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long get() = 2

    val tables: List<String> = SchemaV1.tables + """
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
        """.trimIndent()

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        tables.forEach { driver.execute(null, it, 0) }
        return QueryResult.Unit
    }

    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> =
        error("SchemaV2 is a starting point; migrate with NorthformDb.Schema")
}
