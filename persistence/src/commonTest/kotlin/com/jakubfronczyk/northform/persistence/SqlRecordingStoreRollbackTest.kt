package com.jakubfronczyk.northform.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.ports.FlushBatch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A flush is one transaction (D83): when a write inside it fails, nothing of the batch stays, so the
 * session's retry (the same samples plus newer ones, D94) adds no duplicates. The driver is wrapped
 * (`by inner`: Kotlin's interface delegation) so that one chosen `execute` throws like a full disk.
 */
class SqlRecordingStoreRollbackTest {
    private class FailingDriver(private val inner: SqlDriver) : SqlDriver by inner {
        private var executes = 0
        /** Which `execute` of the NEXT calls throws; counting restarts whenever it is set. */
        var failOnExecute = -1
            set(value) { field = value; executes = 0 }
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if (++executes == failOnExecute) throw IllegalStateException("disk full (execute #$executes: ${sql.lineSequence().first()})")
            return inner.execute(identifier, sql, parameters, binders)
        }
    }

    private val t0 = Instant.parse("2026-10-10T06:00:00Z")
    private val id = RecordingId("rec-1")
    private fun batch(from: Int, to: Int, persistedAt: Int) = FlushBatch(
        id,
        hr = (from..to).map { HrSample(t0 + it.seconds, 120 + it) },
        fixes = (from..to).map { LocationFix(t0 + it.seconds, 52.0 + it * 1e-5, 21.0, 5.0) },
        events = listOf(RecordingEvent(t0 + from.seconds, EventKind.Pause, EventSource.Manual)),
        persistedAt = t0 + persistedAt.seconds,
    )

    @Test
    fun a_failed_write_inside_a_flush_leaves_nothing_of_the_batch() = runTest {
        val raw = inMemoryDriver()
        val driver = FailingDriver(raw)
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            store.create(Recording(id = id, type = RecordingType.Run, state = RecordingState.Active, startUtc = t0, tzOffset = 0))
            store.flush(batch(1, 3, 3))

            // the second flush: hr chunk goes in, the gps chunk insert (its 2nd execute) fails
            driver.failOnExecute = 2
            assertFailsWith<IllegalStateException> { store.flush(batch(4, 6, 6)) }
            driver.failOnExecute = -1

            val afterFailure = assertNotNull(store.load(id))
            assertEquals(3, afterFailure.hr.size, "the hr chunk written before the failure was rolled back")
            assertEquals(3, afterFailure.fixes.size)
            assertEquals(1, afterFailure.events.size)
            assertEquals(t0 + 3.seconds, afterFailure.lastPersistedAt, "the heartbeat did not move")

            // the session retries with the same samples plus newer ones: no duplicates
            store.flush(batch(4, 8, 8))
            val retried = assertNotNull(store.load(id))
            assertEquals((1..8).map { HrSample(t0 + it.seconds, 120 + it) }, retried.hr)
            assertEquals(8, retried.fixes.size)
            assertEquals(2, retried.events.size)
            assertEquals(t0 + 8.seconds, retried.lastPersistedAt)
        } finally {
            raw.close()
        }
    }

    @Test
    fun a_failed_create_leaves_no_recording() = runTest {
        val raw = inMemoryDriver()
        val driver = FailingDriver(raw)
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            driver.failOnExecute = 1
            assertFailsWith<IllegalStateException> { store.create(Recording(id = id, type = RecordingType.Run, state = RecordingState.Active, startUtc = t0, tzOffset = 0)) }
            driver.failOnExecute = -1
            assertNull(store.load(id))
            assertNull(store.unfinished())
        } finally {
            raw.close()
        }
    }
}
