package com.poc.ignoretest

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Proves @Ignore is actually honored end-to-end: the ignored column never exists in the real
 * schema, and a row written by Room's own generated Dao (through the real `ignore_demo` table,
 * which never has a `computedLabel` column) reads back correctly through our own generated
 * readIgnoreDemo() - which must fall back to the property's default value rather than trying
 * to read a nonexistent column.
 */
class IgnoreDemoTest {

    @Test
    fun `Ignore'd property is excluded from the schema and defaults on read-back`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { IgnoreDemoDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        assertFalse("CREATE TABLE must not mention the @Ignore'd column", IgnoreDemoCreateTableSql().contains("computedLabel"))
        assertFalse("ExpectedColumns must not mention the @Ignore'd column", IgnoreDemoExpectedColumns().any { it.name == "computedLabel" })

        db.dao().insert(IgnoreDemo(name = "alice", computedLabel = "some-runtime-only-value"))
        val row = db.dao().getAll().single()
        assertEquals("alice", row.name)
        // the ignored field was never persisted, so it must come back as its constructor
        // default - not the value we passed at insert time (which SQLite never even saw).
        assertEquals("unset", row.computedLabel)

        db.close()
    }
}
