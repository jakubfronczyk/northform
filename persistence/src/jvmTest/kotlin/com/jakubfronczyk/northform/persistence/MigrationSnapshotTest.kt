package com.jakubfronczyk.northform.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.reliability.ReliabilityRecord
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The committed snapshot `databases/1.db` (written by `generateCommonMainNorthformDbSchema`, read by
 * `verifySqlDelightMigration`) is also opened at runtime: a real file on disk, like the phone's. JVM
 * only: the file is found by the module's working directory, which a simulator test does not have.
 */
class MigrationSnapshotTest {
    private val snapshot = File("src/commonMain/sqldelight/databases/1.db")

    @Test
    fun the_committed_v1_snapshot_opens_migrates_and_takes_a_run() = runTest {
        val copy = File.createTempFile("northform-v1-", ".db").also { snapshot.copyTo(it, overwrite = true) }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.path}")
        try {
            NorthformDb.Schema.migrate(driver, oldVersion = 1, newVersion = NorthformDb.Schema.version)
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            val recording = Recording(id = RecordingId("after-upgrade"), type = RecordingType.Walk, state = RecordingState.Active, startUtc = Instant.parse("2026-10-10T06:00:00Z"), tzOffset = 0)
            store.create(recording)
            assertEquals(recording, assertNotNull(store.load(recording.id)).recording)
            // v2 (1.sqm): the real v1 file, migrated, takes the reliability row too.
            val record = ReliabilityRecord(drops = 1, reconnects = 1, reconnectTimes = listOf(30.seconds), hrLost = 35.seconds, gpsLost = 0.seconds, crashes = 0, bluetoothOff = 1)
            store.saveReliability(recording.id, record)
            assertEquals(record, store.reliability(recording.id))
        } finally {
            driver.close()
            copy.delete()
        }
    }

    @Test
    fun the_pinned_v1_ddl_matches_the_committed_snapshot_column_for_column() {
        val pinned = inMemoryDriver(schema = SchemaV1).use { it.columns() }
        val committed = JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}").use { it.columns() }
        assertEquals(committed, pinned)
    }

    @Test
    fun the_pinned_v2_ddl_matches_the_committed_snapshot_column_for_column() {
        val pinned = inMemoryDriver(schema = SchemaV2).use { it.columns() }
        val committed = JdbcSqliteDriver("jdbc:sqlite:src/commonMain/sqldelight/databases/2.db").use { it.columns() }
        assertEquals(committed, pinned)
    }

    /** `table.column type notnull pk default` for every user table, from SQLite's own catalogue. */
    private fun SqlDriver.columns(): List<String> {
        val tables = strings("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { getString(0)!! }
        return tables.flatMap { table ->
            strings("PRAGMA table_info($table)") { "$table.${getString(1)} ${getString(2)} notnull=${getLong(3)} pk=${getLong(5)} default=${getString(4)}" }
        }
    }

    private fun SqlDriver.strings(sql: String, row: SqlCursor.() -> String): List<String> =
        executeQuery(null, sql, { cursor -> QueryResult.Value(buildList { while (cursor.next().value) add(cursor.row()) }) }, 0).value

    private inline fun <T> SqlDriver.use(body: (SqlDriver) -> T): T = try { body(this) } finally { close() }
}
