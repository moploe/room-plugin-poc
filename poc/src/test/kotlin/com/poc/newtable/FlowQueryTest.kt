package com.poc.newtable

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.poc.plugin.runtime.eq
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises query<Entity>Flow(where, ...), the reactive counterpart to the Where-query Dao
 * wrapper, built on RoomDatabase.invalidationTracker (confirmed empirically in
 * InvalidationTrackerFlowExperiment to emit immediately on collection, then again on every
 * write to the tracked table).
 */
class FlowQueryTest {

    private fun tempDbPath(): String {
        val f = File.createTempFile("flow-query-poc", ".db")
        f.delete()
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test
    fun `queryAccountV2Flow emits current data immediately, then again after a matching insert`() = runBlocking {
        val db = AccountDbV4Builder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()

        // an unrelated row, inserted before collection starts - the WHERE clause must
        // exclude it from every emission, not just the first.
        db.accountDao().insert(AccountV2(name = "unrelated"))

        val flow = db.queryAccountV2Flow(AccountV2Columns.Name eq "root")
        val firstEmitted = CompletableDeferred<Unit>()
        val resultsDeferred = async {
            flow.onEach { if (!firstEmitted.isCompleted) firstEmitted.complete(Unit) }.take(2).toList()
        }

        // block until the collector has actually received its first emission before writing,
        // so the write can't race ahead of collection starting (would make the test flaky).
        withTimeout(5.seconds) { firstEmitted.await() }

        db.accountDao().insert(AccountV2(name = "root"))

        val results = withTimeout(10.seconds) { resultsDeferred.await() }
        assertEquals(2, results.size)
        assertTrue("expected no matching rows before the insert", results[0].isEmpty())
        assertEquals(listOf("root"), results[1].map { it.name })

        db.close()
    }

    @Test
    fun `queryAccountV2Flow re-emits after a write to an UNRELATED row in the same table too`() = runBlocking {
        // InvalidationTracker tracks per-TABLE, not per-row - any write to `account` should
        // trigger a re-emission, even if the specific row written doesn't match the WHERE.
        val db = AccountDbV4Builder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()

        val flow = db.queryAccountV2Flow(AccountV2Columns.Name eq "root")
        val firstEmitted = CompletableDeferred<Unit>()
        val resultsDeferred = async {
            flow.onEach { if (!firstEmitted.isCompleted) firstEmitted.complete(Unit) }.take(2).toList()
        }
        withTimeout(5.seconds) { firstEmitted.await() }

        db.accountDao().insert(AccountV2(name = "someone-else"))

        val results = withTimeout(10.seconds) { resultsDeferred.await() }
        assertEquals(2, results.size)
        assertTrue(results[0].isEmpty())
        assertTrue("second emission still shouldn't match the WHERE, but must still fire", results[1].isEmpty())

        db.close()
    }
}
