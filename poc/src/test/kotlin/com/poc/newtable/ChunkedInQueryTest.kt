package com.poc.newtable

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.poc.plugin.runtime.chunkedInQuery
import com.poc.plugin.runtime.chunkedInWrite
import com.poc.plugin.runtime.eq
import com.poc.plugin.runtime.`in`
import com.poc.wheredsl.Product
import com.poc.wheredsl.ProductColumns
import com.poc.wheredsl.ProductDbBuilder
import com.poc.wheredsl.deleteProductWhere
import com.poc.wheredsl.query
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Proves every generated batch/nested-relation query survives an id list bigger than
 * SQLite's classic 999 bound-parameter cap, by actually exceeding it (not just inspecting
 * generated code) - before this, `getAllAccountV2WithSessionV2List(ids)` with more than 999
 * ids would have thrown "too many SQL variables" on any SQLite build enforcing that limit.
 */
class ChunkedInQueryTest {

    @Test
    fun `batch relation fetch works past the 900-id chunk boundary`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV5_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val accountIds = (1..1200).map { i ->
            val id = db.accountDao().insert(AccountV2(name = "acct-$i"))
            db.sessionDao().insert(SessionV2(accountId = id, token = "tok-$i"))
            id
        }

        // both the parent query (WHERE id IN (accountIds)) and the child query (WHERE
        // accountId IN (parentIds)) cross the 900-item chunk boundary here.
        val batch = db.getAllAccountV2WithSessionV2List(accountIds)
        assertEquals(1200, batch.size)
        assertEquals(accountIds.toSet(), batch.map { it.accountV2.id }.toSet())
        // every account's own session must have survived the chunked child-side query too,
        // not just the parent-side one.
        assertEquals(setOf(true), batch.map { it.sessionV2List.size == 1 }.toSet())

        // a nonexistent id mixed into an otherwise-huge list must not cause a mismatch -
        // still only the real accounts come back, chunk boundaries or not.
        val withMissing = db.getAllAccountV2WithSessionV2List(accountIds + 999_999_999L)
        assertEquals(1200, withMissing.size)

        db.close()
    }

    @Test
    fun `getAllXWithY still returns nothing for an empty id list, chunking or not`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV5_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()
        assertEquals(0, db.getAllAccountV2WithSessionV2List(emptyList()).size)
        assertNull(db.getAccountV2WithSessionV2List(999_999L))
        db.close()
    }

    @Test
    fun `chunkedInQuery lets a hand-authored Where in() clause survive past the 999-parameter limit`() = runBlocking {
        val f = File.createTempFile("chunked-in-query-poc", ".db")
        f.delete()
        f.deleteOnExit()
        val db = ProductDbBuilder(f.absolutePath).setDriver(BundledSQLiteDriver()).build()

        val ids = (1..1200).map { i -> db.dao().insert(Product(name = "item-$i", price = i.toDouble())) }

        // a single `ProductColumns.Id in ids` Where here would blow SQLite's ~999-parameter
        // cap for one statement - chunkedInQuery splits it into <=900-id slices and merges the
        // results, exactly what the processor's own generated batch-fetch queries do internally.
        val fetched = chunkedInQuery(ids) { chunk -> db.dao().query(ProductColumns.Id `in` chunk) }
        assertEquals(1200, fetched.size)
        assertEquals(ids.toSet(), fetched.map { it.id }.toSet())

        // a nonexistent id mixed into the list must not throw or cause a mismatch.
        val withMissing = chunkedInQuery(ids + 999_999_999L) { chunk -> db.dao().query(ProductColumns.Id `in` chunk) }
        assertEquals(1200, withMissing.size)

        assertEquals(emptyList<Product>(), chunkedInQuery(emptyList()) { chunk -> db.dao().query(ProductColumns.Id `in` chunk) })

        db.close()
    }

    @Test
    fun `chunkedInWrite lets deleteProductWhere's hand-authored Where in() clause survive past the 999-parameter limit too`() = runBlocking {
        val f = File.createTempFile("chunked-in-write-poc", ".db")
        f.delete()
        f.deleteOnExit()
        val db = ProductDbBuilder(f.absolutePath).setDriver(BundledSQLiteDriver()).build()

        val toDelete = (1..1200).map { i -> db.dao().insert(Product(name = "delete-me-$i", price = i.toDouble())) }
        val survivor = db.dao().insert(Product(name = "keep-me", price = 0.0))

        // a single `deleteProductWhere(ProductColumns.Id in toDelete)` here would blow SQLite's
        // ~999-parameter cap for one statement - chunkedInWrite splits it into <=900-id
        // slices, deletes each chunk, and sums the affected-row counts across all chunks.
        val deleted = chunkedInWrite(toDelete) { chunk -> db.deleteProductWhere(ProductColumns.Id `in` chunk) }
        assertEquals(1200, deleted)

        // every deleted id must actually be gone, chunk boundaries or not - and querying the
        // 1200-id list back also has to go through chunkedInQuery for the same reason.
        val stillThere = chunkedInQuery(toDelete) { chunk -> db.dao().query(ProductColumns.Id `in` chunk) }
        assertEquals(emptyList<Product>(), stillThere)
        assertEquals(listOf("keep-me"), db.dao().query(ProductColumns.Id eq survivor).map { it.name })

        assertEquals(0, chunkedInWrite(emptyList()) { chunk -> db.deleteProductWhere(ProductColumns.Id `in` chunk) })

        db.close()
    }
}
