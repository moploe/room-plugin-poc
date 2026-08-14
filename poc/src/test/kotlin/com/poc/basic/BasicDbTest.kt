package com.poc.basic

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BasicDbTest {
    @Test
    fun insertAndQuery() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { BasicDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        db.dao().insert(BasicItem(name = "hello"))
        val all = db.dao().getAll()

        assertEquals(1, all.size)
        assertEquals("hello", all[0].name)

        db.close()
    }
}
