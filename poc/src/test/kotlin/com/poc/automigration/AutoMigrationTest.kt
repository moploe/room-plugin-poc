package com.poc.automigration

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutoMigrationTest {

    private fun tempDbPath(): String {
        val f = File.createTempFile("automigration-poc", ".db")
        f.delete()
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test
    fun `runtime diff based migration is accepted by Room schema validation and data survives`() = runBlocking {
        val path = tempDbPath()

        // 1. "code before the change": open v1, insert a row, close.
        val v1 = PersonDbV1Builder(path)
            .setDriver(BundledSQLiteDriver())
            .build()
        v1.dao().insert(PersonV1(name = "Alice"))
        v1.close()

        // 2. "code after the change": someone added `age` to the entity. We do NOT hand-write
        //    "ALTER TABLE person ADD COLUMN age ..." nor a Migration(1,2){} wrapper, and the
        //    call site below doesn't even mention AutoMigrations() - PersonDbV2Builder(path)
        //    is entirely :plugin-processor generated, migrations pre-wired inside it.
        val v2 = PersonDbV2Builder(path)
            .setDriver(BundledSQLiteDriver())
            .build()

        // If Room's own onValidateSchema() disagrees with what our runtime ALTER produced,
        // this throws IllegalStateException here.
        val rows = v2.dao().getAll()

        assertEquals(1, rows.size)
        assertEquals("Alice", rows[0].name)
        assertEquals(0, rows[0].age) // DEFAULT 0 applied to the pre-existing row

        v2.dao().insert(PersonV2(name = "Bob", age = 30))
        val rows2 = v2.dao().getAll()
        assertEquals(2, rows2.size)
        assertTrue(rows2.any { it.name == "Bob" && it.age == 30 })

        v2.close()
    }

    @Test
    fun `negative control - opening v2 with no migration at all does throw, proving validation is real`() = runBlocking {
        val path = tempDbPath()

        val v1 = Room.databaseBuilder(path) { PersonDbV1_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()
        v1.dao().insert(PersonV1(name = "Alice"))
        v1.close()

        val v2 = Room.databaseBuilder(path) { PersonDbV2_Impl() }
            .setDriver(BundledSQLiteDriver())
            // no addMigrations(), no fallbackToDestructiveMigration()
            .build()

        assertThrows(Exception::class.java) {
            runBlocking { v2.dao().getAll() }
        }

        v2.close()
    }
}
