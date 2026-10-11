package com.jakubfronczyk.northform.persistence

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * A schema version as it shipped, pinned (persistence/03): its DDL frozen here while `Recording.sq` moves on
 * with every `.sqm`, so a test can build the database a phone has today and migrate it forward on both
 * platforms. The JVM test checks each against its committed snapshot `databases/<version>.db` column for
 * column. Idiom: an `abstract class` implementing the generated schema's interface,
 * `SqlSchema<QueryResult.Value<Unit>>` (the type the drivers take); each version is an `object` extending it.
 *
 * A new version (recipe step 5): add `object SchemaVn : PinnedSchema(n, …)` and list it in [all].
 */
abstract class PinnedSchema(override val version: Long, val tables: List<String>) : SqlSchema<QueryResult.Value<Unit>> {
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        tables.forEach { driver.execute(null, it, 0) }
        return QueryResult.Unit
    }

    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> =
        error("schema v$version is a starting point; migrate with NorthformDb.Schema")

    companion object {
        /** Every shipped version before the current one, oldest first. */
        val all: List<PinnedSchema> get() = listOf(SchemaV1, SchemaV2)
    }
}
