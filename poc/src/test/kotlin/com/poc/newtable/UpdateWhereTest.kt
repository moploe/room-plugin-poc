package com.poc.newtable

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.poc.plugin.runtime.decrement
import com.poc.plugin.runtime.divide
import com.poc.plugin.runtime.eq
import com.poc.plugin.runtime.increment
import com.poc.plugin.runtime.multiply
import com.poc.plugin.runtime.plus
import com.poc.plugin.runtime.set
import com.poc.plugin.runtime.setNull
import com.poc.plugin.runtime.setToColumn
import com.poc.wheredsl.Product
import com.poc.wheredsl.ProductColumns
import com.poc.wheredsl.ProductDbBuilder
import com.poc.wheredsl.query
import com.poc.wheredsl.updateProductWhere
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises update<Entity>Where(where, set), the useWriterConnection-based UPDATE helper
 * (bypasses @RawQuery for the same write-limitation reason delete<Entity>Where does).
 */
class UpdateWhereTest {

    private fun tempDbPath(): String {
        val f = File.createTempFile("update-where-poc", ".db")
        f.delete()
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test
    fun `single-column set updates only matching rows and reports the correct count`() = runBlocking {
        val db = AccountDbV4Builder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1"))
        db.sessionDao().insert(SessionV2(accountId = accountId, token = "s2"))
        val otherAccountId = db.accountDao().insert(AccountV2(name = "other"))
        db.sessionDao().insert(SessionV2(accountId = otherAccountId, token = "untouched"))

        // moves both of accountId's sessions to otherAccountId - accountId isn't unique so
        // this avoids colliding with `token`'s unique index while still updating 2 rows
        // at once with a single SET value.
        val updated = db.updateSessionV2Where(SessionV2Columns.AccountId eq accountId, SessionV2Columns.AccountId set otherAccountId)
        assertEquals(2, updated)

        assertTrue(db.sessionDao().query(SessionV2Columns.AccountId eq accountId).isEmpty())
        val moved = db.sessionDao().query(SessionV2Columns.AccountId eq otherAccountId)
        assertEquals(setOf("s1", "s2", "untouched"), moved.map { it.token }.toSet())

        db.close()
    }

    @Test
    fun `multi-column set via + combines assignments, and bind order survives a Where with its own binders`() = runBlocking {
        val db = AccountDbV4Builder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        val target = db.sessionDao().insert(SessionV2(accountId = accountId, token = "old-token"))
        val untouchedSibling = db.sessionDao().insert(SessionV2(accountId = accountId, token = "sibling"))
        val parentForOthers = db.sessionDao().insert(SessionV2(accountId = accountId, token = "parent"))

        // WHERE (on `id`, which has its own bound parameter) narrows to exactly one row -
        // this proves SET binders (which come first in the generated SQL) don't collide
        // with WHERE binders' positions, since both are bound in the same statement.
        val updated = db.updateSessionV2Where(
            SessionV2Columns.Id eq target,
            (SessionV2Columns.Token set "new-token") + (SessionV2Columns.ParentSessionId set parentForOthers),
        )
        assertEquals(1, updated)

        val row = db.sessionDao().query(SessionV2Columns.Id eq target).single()
        assertEquals("new-token", row.token)
        assertEquals(parentForOthers, row.parentSessionId)
        // the sibling row must be untouched - proves the WHERE actually narrowed the UPDATE
        val sibling = db.sessionDao().query(SessionV2Columns.Id eq untouchedSibling).single()
        assertEquals("sibling", sibling.token)
        assertNull(sibling.parentSessionId)

        db.close()
    }

    @Test
    fun `setNull clears a nullable FK column`() = runBlocking {
        val db = AccountDbV4Builder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        val parent = db.sessionDao().insert(SessionV2(accountId = accountId, token = "parent"))
        val child = db.sessionDao().insert(SessionV2(accountId = accountId, token = "child", parentSessionId = parent))

        val updated = db.updateSessionV2Where(SessionV2Columns.Id eq child, SessionV2Columns.ParentSessionId.setNull())
        assertEquals(1, updated)

        val row = db.sessionDao().query(SessionV2Columns.Id eq child).single()
        assertNull(row.parentSessionId)

        db.close()
    }

    @Test
    fun `no matching rows updates nothing and returns 0`() = runBlocking {
        val db = AccountDbV4Builder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1"))

        val updated = db.updateSessionV2Where(SessionV2Columns.AccountId eq 999_999L, SessionV2Columns.Token set "x")
        assertEquals(0, updated)

        val untouched = db.sessionDao().query(SessionV2Columns.AccountId eq accountId)
        assertEquals(listOf("s1"), untouched.map { it.token })
        assertTrue(true)

        db.close()
    }

    @Test
    fun `increment computes against SQLite's current value, not a stale client-side read`() = runBlocking {
        val db = ProductDbBuilder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()
        val id = db.dao().insert(Product(name = "widget", price = 10.0))

        val updated = db.updateProductWhere(ProductColumns.Id eq id, ProductColumns.Price increment 5.0)
        assertEquals(1, updated)
        assertEquals(15.0, db.dao().query(ProductColumns.Id eq id).single().price, 0.0001)

        db.updateProductWhere(ProductColumns.Id eq id, ProductColumns.Price decrement 3.0)
        assertEquals(12.0, db.dao().query(ProductColumns.Id eq id).single().price, 0.0001)

        // the real point of `increment`: 50 concurrent "+1" updates against the same row must
        // all land, because SQLite computes `price = price + ?` against whatever the column's
        // value is *at UPDATE time* inside its own writer connection - not a value any client
        // read earlier and could race. A naive "read then write price+1" implementation would
        // lose updates here under real concurrency.
        val zeroed = db.dao().insert(Product(name = "counter", price = 0.0))
        (1..50).map { async { db.updateProductWhere(ProductColumns.Id eq zeroed, ProductColumns.Price increment 1.0) } }.awaitAll()
        assertEquals(50.0, db.dao().query(ProductColumns.Id eq zeroed).single().price, 0.0001)

        db.close()
    }

    @Test
    fun `multiply and divide compute against SQLite's current value`() = runBlocking {
        val db = ProductDbBuilder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()
        val id = db.dao().insert(Product(name = "widget", price = 10.0))

        db.updateProductWhere(ProductColumns.Id eq id, ProductColumns.Price multiply 3.0)
        assertEquals(30.0, db.dao().query(ProductColumns.Id eq id).single().price, 0.0001)

        db.updateProductWhere(ProductColumns.Id eq id, ProductColumns.Price divide 5.0)
        assertEquals(6.0, db.dao().query(ProductColumns.Id eq id).single().price, 0.0001)

        db.close()
    }

    @Test
    fun `setToColumn copies another column's value with zero bind placeholders, mixed with a normal assignment`() = runBlocking {
        val db = AccountDbV4Builder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        // self-referencing parentSessionId FK is satisfied trivially here: by the time we UPDATE,
        // `target`'s own row already exists, so "parentSessionId = id" (pointing at itself) is a
        // valid FK target - lets this test stay FK-safe without needing a second Long column.
        val target = db.sessionDao().insert(SessionV2(accountId = accountId, token = "old-token"))
        val untouchedSibling = db.sessionDao().insert(SessionV2(accountId = accountId, token = "sibling"))

        // mixes a 0-placeholder assignment (setToColumn) with a 1-placeholder one (set), then a
        // WHERE with its own bound parameter - this is the exact case the placeholderCount
        // refactor (using set.placeholderCount instead of set.assignments.size for the WHERE
        // binder offset) exists to get right; a regression here would bind "new-token" as the
        // WHERE's `id` parameter instead of the Token column, or vice versa.
        val updated = db.updateSessionV2Where(
            SessionV2Columns.Id eq target,
            (SessionV2Columns.ParentSessionId setToColumn SessionV2Columns.Id) + (SessionV2Columns.Token set "new-token"),
        )
        assertEquals(1, updated)

        val row = db.sessionDao().query(SessionV2Columns.Id eq target).single()
        assertEquals(target, row.parentSessionId)
        assertEquals("new-token", row.token)
        // sibling untouched proves the WHERE's own binder still landed at the right position
        val sibling = db.sessionDao().query(SessionV2Columns.Id eq untouchedSibling).single()
        assertEquals("sibling", sibling.token)
        assertNull(sibling.parentSessionId)

        db.close()
    }
}
