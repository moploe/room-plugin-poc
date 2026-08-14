package com.poc.ambiguousfk

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * `Book` has two FKs to `Author` (`authorId`, `editorId`). Relation generation is keyed only
 * by the (parent, child) entity pair, so only the first FK encountered (`authorId`) gets a
 * generated relation - see the ColumnsProcessor warning this triggers at build time. This just
 * proves that's a clean, working (if incomplete) outcome: the build succeeds, no generated
 * file/name collides, and the one relation that IS generated works correctly.
 */
class AmbiguousFkTest {

    @Test
    fun `first FK between an ambiguous pair still gets a correct, working relation`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AmbiguousFkDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val author = db.authorDao().insert(Author(name = "Tolkien"))
        val editor = db.authorDao().insert(Author(name = "Editor Bob"))
        db.bookDao().insert(Book(title = "The Hobbit", authorId = author, editorId = editor))
        db.bookDao().insert(Book(title = "LOTR", authorId = author, editorId = null))

        val result = db.getAuthorWithBookList(author)
        assertNotNull(result)
        assertEquals("Tolkien", result!!.author.name)
        assertEquals(setOf("The Hobbit", "LOTR"), result.bookList.map { it.title }.toSet())

        db.close()
    }
}
