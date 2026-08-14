package com.poc.jsonconv

import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.poc.plugin.runtime.eq
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonConverterPrecedenceTest {

    private suspend fun rawShippingColumn(db: OrderDb, table: String, id: Long): String =
        db.useReaderConnection { transactor ->
            transactor.usePrepared("SELECT shipping FROM $table WHERE id = ?") { stmt ->
                stmt.bindLong(1, id)
                stmt.step()
                stmt.getText(0)
            }
        }

    @Test
    fun `field-level ColumnTypeConverters overrides the database-level default`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { OrderDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val shipping = Shipping(address = "1 Infinite Loop", zip = "95014")

        val id = db.dao().insert(OrderEntity(shipping = shipping))
        val raw = rawShippingColumn(db, "orders", id)

        // the field-scoped CustomShippingConverter should have been used, not GlobalJsonConverters
        assertTrue("expected custom-converter output, got: $raw", raw.startsWith("CUSTOM:"))

        val roundTripped = db.dao().getById(id)
        assertEquals(shipping, roundTripped?.shipping)

        db.close()
    }

    @Test
    fun `database-level default still applies where no field override exists`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { OrderDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val shipping = Shipping(address = "221B Baker Street", zip = "NW16XE")

        val id = db.dao().insertDefault(OrderEntityDefault(shipping = shipping))
        val raw = rawShippingColumn(db, "orders_default", id)

        assertTrue("expected plain JSON from the global default, got: $raw", !raw.startsWith("CUSTOM:"))
        assertTrue(raw.contains("\"address\""))

        val roundTripped = db.dao().getDefaultById(id)
        assertEquals(shipping, roundTripped?.shipping)

        db.close()
    }

    @Test
    fun `entities using only the default JSON converter are now readableForRelations - jsonDecode round-trips through relation-fetch codegen too`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { OrderDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val shipping = Shipping(address = "742 Evergreen Terrace", zip = "89019")
        val id = db.dao().insertDefault(OrderEntityDefault(shipping = shipping))

        // queryOrderEntityDefaultFlow only exists at all because OrderEntityDefault is now
        // readableForRelations (it has no explicit @ColumnTypeConverters override) - this
        // wouldn't have compiled before this change. It goes through the generated
        // readOrderEntityDefault() (relation-fetch codegen, calling jsonDecode<Shipping>
        // directly), NOT through Room's own Dao/@Query path used by getDefaultById above.
        val viaRelationCodegen = db.queryOrderEntityDefaultFlow(OrderEntityDefaultColumns.Id eq id).first().single()
        assertEquals(shipping, viaRelationCodegen.shipping)

        db.close()
    }
}
