package com.jakubfronczyk.northform.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * A fresh, empty database that lives in memory and dies with the driver: the store's tests and the
 * simulator. The one `expect/actual` the architecture allows (D3): a leaf factory for the SQL driver,
 * nothing above it varies per platform.
 *
 * [schema] is the schema the database is created at: the current one by default; a migration test
 * passes an older, pinned schema and then migrates it forward (persistence/03).
 */
expect fun inMemoryDriver(name: String = "northform-memory", schema: SqlSchema<QueryResult.Value<Unit>> = NorthformDb.Schema): SqlDriver
