package com.poc.flowexperiment

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.poc.newtable.AccountDbV1Builder
import com.poc.newtable.AccountV1
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Isolated experiment (no generated code) to determine InvalidationTracker.createFlow's
 * emission semantics empirically before designing task 35's Flow-query codegen on top of
 * it: does the Flow emit once immediately on collection (so callers see current data right
 * away), or does it only emit after the FIRST write/invalidation (which would mean a
 * freshly-collected Flow shows nothing until some other code mutates the table)?
 */
class InvalidationTrackerFlowExperiment {

    private fun tempDbPath(): String {
        val f = File.createTempFile("flow-experiment", ".db")
        f.delete()
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test
    fun `createFlow emits once immediately on collection, with no prior write`() = runBlocking {
        val db = AccountDbV1Builder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()

        val flow = db.invalidationTracker.createFlow("account")
        val first = withTimeoutOrNull(5.seconds) { flow.first() }
        assertNotNull("expected an initial emission with no prior write", first)

        db.close()
    }

    @Test
    fun `createFlow emits again after a write to the tracked table`() = runBlocking {
        val db = AccountDbV1Builder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()

        val flow = db.invalidationTracker.createFlow("account")
        val initial = withTimeoutOrNull(5.seconds) { flow.first() }
        assertNotNull(initial)

        // second collection after a write - must also complete, proving invalidation fires
        db.dao().insert(AccountV1(name = "trigger"))
        val afterWrite = withTimeoutOrNull(10.seconds) { flow.first() }
        assertNotNull("expected an emission after a write to the tracked table", afterWrite)
        assertEquals(setOf("account"), afterWrite)

        db.close()
    }
}
