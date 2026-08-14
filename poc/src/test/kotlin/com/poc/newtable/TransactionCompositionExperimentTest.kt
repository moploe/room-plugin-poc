package com.poc.newtable

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.poc.plugin.runtime.eq
import com.poc.plugin.runtime.set
import com.poc.wheredsl.Product
import com.poc.wheredsl.ProductColumns
import com.poc.wheredsl.ProductDbBuilder
import com.poc.wheredsl.query
import com.poc.wheredsl.updateProductWhere
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Empirically checks whether two independently-generated write calls (each opening its own
 * useWriterConnection internally) can be composed into ONE atomic transaction by nesting them
 * inside a caller-authored `db.useWriterConnection { ... }` block - i.e. whether "transactional
 * composition" is a real gap requiring new API surface, or whether Room's own writer-connection
 * reentrancy already makes it work with zero code changes on our side. Guarded by a timeout so
 * a genuine deadlock fails the test instead of hanging the suite forever.
 */
class TransactionCompositionExperimentTest {

    private fun tempDbPath(): String {
        val f = File.createTempFile("txn-composition-poc", ".db")
        f.delete()
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test
    fun `plain useWriterConnection does NOT start a transaction - two nested writes each commit independently`() = runBlocking {
        val db = ProductDbBuilder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()
        val id1 = db.dao().insert(Product(name = "a", price = 1.0))
        val id2 = db.dao().insert(Product(name = "b", price = 2.0))

        val failed = withTimeout(10.seconds) {
            runCatching {
                db.useWriterConnection { _ ->
                    db.updateProductWhere(ProductColumns.Id eq id1, ProductColumns.Price set 100.0)
                    db.updateProductWhere(ProductColumns.Id eq id2, ProductColumns.Price set 200.0)
                    throw RuntimeException("force rollback")
                }
            }
        }
        assertTrue("expected the forced exception to propagate", failed.isFailure)

        // useWriterConnection only ACQUIRES the connection, it never issues BEGIN/COMMIT -
        // each generated update<Entity>Where call is its own separate implicit-autocommit
        // statement, so both updates persist despite the "outer" block throwing.
        assertEquals(100.0, db.dao().query(ProductColumns.Id eq id1).single().price, 0.0001)
        assertEquals(200.0, db.dao().query(ProductColumns.Id eq id2).single().price, 0.0001)

        db.close()
    }

    @Test
    fun `withWriteTransaction DOES compose two generated write calls into one atomic transaction`() = runBlocking {
        val db = ProductDbBuilder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()
        val id1 = db.dao().insert(Product(name = "a", price = 1.0))
        val id2 = db.dao().insert(Product(name = "b", price = 2.0))

        val failed = withTimeout(10.seconds) {
            runCatching {
                db.withWriteTransaction {
                    db.updateProductWhere(ProductColumns.Id eq id1, ProductColumns.Price set 100.0)
                    db.updateProductWhere(ProductColumns.Id eq id2, ProductColumns.Price set 200.0)
                    throw RuntimeException("force rollback")
                }
            }
        }
        assertTrue("expected the forced exception to propagate", failed.isFailure)

        // withWriteTransaction DOES issue a real BEGIN/COMMIT around the block, and Room's
        // connection reentrancy (a coroutine that already holds a confined connection reuses
        // it, per RoomDatabase.useWriterConnection's own docs) means each nested generated
        // call runs on that SAME connection, inside that SAME transaction - so the whole
        // thing rolls back together. No new API surface was needed for this to work: it's
        // already correct as long as the caller reaches for withWriteTransaction instead of
        // plain useWriterConnection to compose multiple generated write calls atomically.
        assertEquals(1.0, db.dao().query(ProductColumns.Id eq id1).single().price, 0.0001)
        assertEquals(2.0, db.dao().query(ProductColumns.Id eq id2).single().price, 0.0001)

        db.close()
    }
}
