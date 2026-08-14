package com.poc.wheredsl

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.poc.plugin.runtime.and
import com.poc.plugin.runtime.between
import com.poc.plugin.runtime.eq
import com.poc.plugin.runtime.gt
import com.poc.plugin.runtime.gte
import com.poc.plugin.runtime.`in`
import com.poc.plugin.runtime.isNull
import com.poc.plugin.runtime.lt
import com.poc.plugin.runtime.lte
import com.poc.plugin.runtime.neq
import com.poc.plugin.runtime.not
import com.poc.plugin.runtime.or
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhereDslTest {

    @Test
    fun `typed where dsl compiles to a correct RoomRawQuery`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { ProductDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val dao = db.dao()
        dao.insert(Product(name = "Widget", price = 5.0))
        dao.insert(Product(name = "Gadget", price = 15.0))
        dao.insert(Product(name = "Gizmo", price = 25.0))

        val expensive = dao.query(ProductColumns.Price gt 10.0)
        assertEquals(setOf("Gadget", "Gizmo"), expensive.map { it.name }.toSet())

        val cheapWidget = dao.query(ProductColumns.Price lt 10.0 and (ProductColumns.Name eq "Widget"))
        assertEquals(1, cheapWidget.size)
        assertEquals("Widget", cheapWidget[0].name)

        val nothingMatches = dao.query(ProductColumns.Price gt 10.0 and (ProductColumns.Name eq "Widget"))
        assertTrue(nothingMatches.isEmpty())

        db.close()
    }

    @Test
    fun `newly added operators bind correctly, including when combined with and-or`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { ProductDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val dao = db.dao()
        dao.insert(Product(name = "Widget", price = 5.0))
        dao.insert(Product(name = "Gadget", price = 15.0))
        dao.insert(Product(name = "Gizmo", price = 25.0))

        // in() combined with and() is exactly the shape that exposed a real bug: each `in`
        // binder was hardcoding its own index instead of using the position and/or assigns
        // it once concatenated, so this specific combination is the regression check.
        val inAndCombo = dao.query((ProductColumns.Name `in` listOf("Widget", "Gizmo")) and (ProductColumns.Price gt 1.0))
        assertEquals(setOf("Widget", "Gizmo"), inAndCombo.map { it.name }.toSet())

        val orCombo = dao.query((ProductColumns.Name eq "Widget") or (ProductColumns.Price gte 25.0))
        assertEquals(setOf("Widget", "Gizmo"), orCombo.map { it.name }.toSet())

        val betweenResult = dao.query(ProductColumns.Price.between(10.0..20.0))
        assertEquals(listOf("Gadget"), betweenResult.map { it.name })

        val neqResult = dao.query(ProductColumns.Name neq "Widget")
        assertEquals(setOf("Gadget", "Gizmo"), neqResult.map { it.name }.toSet())

        val lteResult = dao.query(ProductColumns.Price lte 15.0)
        assertEquals(setOf("Widget", "Gadget"), lteResult.map { it.name }.toSet())

        val notResult = dao.query((ProductColumns.Price gt 10.0).not())
        assertEquals(listOf("Widget"), notResult.map { it.name })

        val isNullResult = dao.query(ProductColumns.Name.isNull)
        assertTrue(isNullResult.isEmpty())

        db.close()
    }
}
