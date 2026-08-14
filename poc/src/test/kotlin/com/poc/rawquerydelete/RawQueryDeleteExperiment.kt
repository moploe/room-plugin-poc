package com.poc.rawquerydelete

import androidx.room3.Room
import androidx.room3.RoomRawQuery
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Isolated experiment: does a bare @RawQuery method returning Int for a DELETE work at
 * all in Room 3, called directly with NO generated wrapper code involved? If this also
 * fails with "attempt to write a readonly database", it confirms @RawQuery is routed
 * through a read-only connection and can't do writes - a Room framework limitation, not
 * a bug in our generated SQL.
 */
class RawQueryDeleteExperiment {
    @Test
    fun `bare RawQuery delete, no generated wrapper code involved`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { WidgetDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val dao = db.dao()
        dao.insert(Widget(name = "Widget"))
        println("EXPERIMENT: inserted, rows before delete = ${dao.getAll().size}")

        val deleted = dao.rawDelete(RoomRawQuery("DELETE FROM widget WHERE name = 'Widget'"))
        println("EXPERIMENT: rawDelete returned $deleted")

        db.close()
    }
}
