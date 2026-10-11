package com.jakubfronczyk.northform.app

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.persistence.SqlRecordingStore
import com.jakubfronczyk.northform.persistence.inMemoryDriver
import com.jakubfronczyk.northform.replay.Fixture
import com.jakubfronczyk.northform.testing.RecordedAlerts
import com.jakubfronczyk.northform.testing.tap
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * persistence/02, write failures end to end on the store the phone uses: a flush that dies inside
 * SQLite (the failing-driver pattern of `SqlRecordingStoreRollbackTest`) comes back to the reducer as
 * `PersistFailed`, the run says "not saving", and the next flush carries everything — no loss, no
 * duplicates — after which it is saving again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SqlWriteFailureTest {
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

    @Test
    fun a_flush_that_dies_in_sqlite_is_retried_by_the_reducer_with_nothing_lost_or_doubled() = runTest {
        val script = Fixture.parse(BundledFixtureText.STRAIGHT_RUN_3MIN)
        val raw = inMemoryDriver("sql-write-failure")
        val driver = FailingDriver(raw)
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            val deps = AppDeps(
                source = RunSource.Replay { script }, store = store, wallClock = virtualWallClock(script.meta.start), alerts = RecordedAlerts(),
                newRecordingId = { "00000000-0000-0000-0000-00000000000e" },
            )
            val run = ActiveRun.open(deps, backgroundScope)
            runCurrent()
            run.session.tap(UserAction.Start)
            advanceTimeBy(14.seconds); runCurrent() // active at 5 s, flushes at 10 s; the next is at 15 s
            assertEquals(RunPhase.Active, run.session.snapshots.value.phase)
            val before = assertNotNull(store.load(run.id))
            assertTrue(before.hr.isNotEmpty() && before.fixes.isNotEmpty())
            assertFalse(run.session.notSaving.value)

            driver.failOnExecute = 2 // the 15 s flush: the hr chunk goes in, the gps chunk throws, the transaction rolls back
            advanceTimeBy(1.seconds); runCurrent()
            driver.failOnExecute = -1
            assertTrue(run.session.notSaving.value, "the failed write is visible to the screen")
            val failed = assertNotNull(store.load(run.id))
            assertEquals(before.lastPersistedAt, failed.lastPersistedAt, "nothing of the failed batch stayed")
            assertEquals(before.hr.size, failed.hr.size)

            advanceTimeBy(5.seconds); runCurrent() // the 20 s flush carries the failed batch plus the newer samples
            assertFalse(run.session.notSaving.value, "saving again")
            val retried = assertNotNull(store.load(run.id))
            assertTrue(retried.lastPersistedAt!! >= script.meta.start + 20.seconds, "${retried.lastPersistedAt}")
            assertEquals(retried.hr.size, retried.hr.map { it.t }.toSet().size, "no sample twice")
            assertEquals(retried.hr.sortedBy { it.t }, retried.hr, "in order")
            assertTrue(retried.hr.size > before.hr.size + 5, "the 15 s and 20 s batches both landed: ${before.hr.size} → ${retried.hr.size}")
        } finally {
            raw.close()
        }
    }
}
