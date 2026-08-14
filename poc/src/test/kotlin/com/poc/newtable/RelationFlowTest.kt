package com.poc.newtable

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.poc.plugin.runtime.like
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
import kotlin.time.Duration.Companion.seconds

/**
 * Proves a *relation's* Flow re-emits on a write to any table the relation actually reads
 * from - not just the "root" entity's own table. A naive implementation watching only the
 * parent table would miss this: inserting a new child session should still push a fresh
 * AccountV2WithSessionV2List even though `account` itself was never written to.
 */
class RelationFlowTest {

    @Test
    fun `getAccountV2WithSessionV2ListFlow re-emits when a CHILD row is inserted, not just the parent`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV5_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))

        val flow = db.getAccountV2WithSessionV2ListFlow(accountId)
        val firstEmitted = CompletableDeferred<Unit>()
        val resultsDeferred = async {
            flow.onEach { if (!firstEmitted.isCompleted) firstEmitted.complete(Unit) }.take(2).toList()
        }
        withTimeout(5.seconds) { firstEmitted.await() }

        // only the `session` table is written to here - `account` itself doesn't change.
        db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1"))

        val results = withTimeout(10.seconds) { resultsDeferred.await() }
        assertEquals(2, results.size)
        assertTrue("expected no sessions before the insert", results[0]!!.sessionV2List.isEmpty())
        assertEquals(listOf("s1"), results[1]!!.sessionV2List.map { it.token })

        db.close()
    }

    @Test
    fun `queryAccountV2WithSessionV2ListFlow filters by a hand-authored Where and still re-emits on a child-only write`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV5_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val matching = db.accountDao().insert(AccountV2(name = "matches-prefix"))
        val nonMatching = db.accountDao().insert(AccountV2(name = "other"))
        db.sessionDao().insert(SessionV2(accountId = nonMatching, token = "irrelevant"))

        val where = AccountV2Columns.Name like "matches%"
        val flow = db.queryAccountV2WithSessionV2ListFlow(where)
        val firstEmitted = CompletableDeferred<Unit>()
        val resultsDeferred = async {
            flow.onEach { if (!firstEmitted.isCompleted) firstEmitted.complete(Unit) }.take(2).toList()
        }
        withTimeout(5.seconds) { firstEmitted.await() }

        // writing a session onto the MATCHING account (not `account` itself) must still
        // trigger a re-emission, and the non-matching account/session must never appear.
        db.sessionDao().insert(SessionV2(accountId = matching, token = "s1"))

        val results = withTimeout(10.seconds) { resultsDeferred.await() }
        assertEquals(2, results.size)
        assertEquals(listOf("matches-prefix"), results[0]!!.map { it.accountV2.name })
        assertTrue("expected no sessions before the insert", results[0]!!.single().sessionV2List.isEmpty())
        assertEquals(listOf("matches-prefix"), results[1]!!.map { it.accountV2.name })
        assertEquals(listOf("s1"), results[1]!!.single().sessionV2List.map { it.token })

        db.close()
    }
}
