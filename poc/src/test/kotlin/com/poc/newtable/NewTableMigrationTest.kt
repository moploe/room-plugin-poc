package com.poc.newtable

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression test for a real bug: the original autoDiffMigration only knew how to ALTER
 * TABLE ADD COLUMN. If a version bump added a whole new @Entity (not just a column on an
 * existing table), PRAGMA table_info on the not-yet-existing table silently returned
 * nothing, and the generated migration then tried "ALTER TABLE session ADD COLUMN ..."
 * against a table that had never been created - a real SQL exception, not a hypothetical.
 */
class NewTableMigrationTest {

    private fun tempDbPath(): String {
        val f = File.createTempFile("newtable-poc", ".db")
        f.delete()
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test
    fun `a brand-new entity's table is created during migration, not just missing columns on old ones`() = runBlocking {
        val path = tempDbPath()

        // 1. "code before the change": only Account exists, insert a row, close.
        val v1 = AccountDbV1Builder(path)
            .setDriver(BundledSQLiteDriver())
            .build()
        v1.dao().insert(AccountV1(name = "root"))
        v1.close()

        // 2. "code after the change": Account gets a new `active` column, AND a whole new
        // Session entity/table (with its own FK + unique index) is introduced. This used
        // to throw here.
        val v2 = AccountDbV2Builder(path)
            .setDriver(BundledSQLiteDriver())
            .build()

        val accounts = v2.accountDao().getAll()
        assertEquals(1, accounts.size)
        assertEquals("root", accounts[0].name)
        assertTrue("existing row should get the DEFAULT for the new column", accounts[0].active)

        // proves the table was really created with the right shape, not just silently
        // skipped - inserting exercises the FK + PK + column types for real.
        val accountId = accounts[0].id
        val sessionId = v2.sessionDao().insert(Session(accountId = accountId, token = "tok-123"))
        val sessions = v2.sessionDao().getAll()
        assertEquals(1, sessions.size)
        assertEquals(sessionId, sessions[0].id)
        assertEquals("tok-123", sessions[0].token)

        v2.close()
    }
}
