package com.poc.nestedmn

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.poc.plugin.runtime.eq
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Proves a many-to-many hop can sit in the *middle* of a nested-relation chain, not just
 * terminate one: Author -(M:N via AuthorBook)-> Book -(1:N)-> Review. Before this, the
 * "spine" of a chain could only be a 1:N relation, so a M:N relation could only ever be the
 * last hop.
 */
class NestedManyToManyChainTest {

    @Test
    fun `author with each book, then each book's own reviews`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { LibraryDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val author = db.authorDao().insert(Author(name = "Ursula"))
        val bookA = db.bookDao().insert(Book(title = "Book A"))
        val bookB = db.bookDao().insert(Book(title = "Book B"))
        // Book A is co-authored - a second author who must NOT see Book B's reviews leak in.
        val coAuthor = db.authorDao().insert(Author(name = "Co-Author"))
        db.authorBookDao().insert(AuthorBook(authorId = author, bookId = bookA))
        db.authorBookDao().insert(AuthorBook(authorId = author, bookId = bookB))
        db.authorBookDao().insert(AuthorBook(authorId = coAuthor, bookId = bookA))

        db.reviewDao().insert(Review(bookId = bookA, stars = 5))
        db.reviewDao().insert(Review(bookId = bookA, stars = 3))
        // Book B deliberately gets no reviews, to prove the leaf level handles empty lists too.

        val result = db.getAuthorWithBookThenReviewList(author)
        assertNotNull(result)
        assertEquals("Ursula", result!!.author.name)
        assertEquals(2, result.bookNestedList.size)

        val byTitle = result.bookNestedList.associateBy { it.book.title }
        assertEquals(setOf(5, 3), byTitle.getValue("Book A").reviewList.map { it.stars }.toSet())
        assertTrue(byTitle.getValue("Book B").reviewList.isEmpty())

        // the batch (ids-based) version must agree, across two authors, without leaking
        // co-authored Book A's reviews asymmetrically or duplicating them.
        val batch = db.getAllAuthorWithBookThenReviewList(listOf(author, coAuthor))
        assertEquals(2, batch.size)
        val coAuthorResult = batch.first { it.author.name == "Co-Author" }
        assertEquals(1, coAuthorResult.bookNestedList.size)
        assertEquals(setOf(5, 3), coAuthorResult.bookNestedList.single().reviewList.map { it.stars }.toSet())

        // a nonexistent author resolves to null, same as every other level already does.
        assertNull(db.getAuthorWithBookThenReviewList(999_999L))

        db.close()
    }

    @Test
    fun `getAuthorWithBookThenReviewListFlow re-emits on a write to the DEEPEST leaf table (review), three hops down from author`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { LibraryDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val author = db.authorDao().insert(Author(name = "Ursula"))
        val book = db.bookDao().insert(Book(title = "Book A"))
        db.authorBookDao().insert(AuthorBook(authorId = author, bookId = book))

        val flow = db.getAuthorWithBookThenReviewListFlow(author)
        val firstEmitted = CompletableDeferred<Unit>()
        val resultsDeferred = async {
            flow.onEach { if (!firstEmitted.isCompleted) firstEmitted.complete(Unit) }.take(2).toList()
        }
        withTimeout(5.seconds) { firstEmitted.await() }

        // `author` and `author_book` are untouched here - only `review`, three hops down
        // the chain, is written to. invalidationTracker.createFlow was given every table in
        // the chain (see the generated code), so this must still trigger a re-emission.
        db.reviewDao().insert(Review(bookId = book, stars = 5))

        val results = withTimeout(10.seconds) { resultsDeferred.await() }
        assertEquals(2, results.size)
        assertTrue(results[0]!!.bookNestedList.single().reviewList.isEmpty())
        assertEquals(listOf(5), results[1]!!.bookNestedList.single().reviewList.map { it.stars })

        db.close()
    }

    @Test
    fun `1-to-1 relation as a mid-chain spine, not just a leaf - book, its detail, then the detail's own notes`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { LibraryDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val bookWithNotes = db.bookDao().insert(Book(title = "Book A"))
        val detailA = db.bookDetailDao().insert(BookDetail(bookId = bookWithNotes, isbn = "111-A"))
        db.bookDetailNoteDao().insert(BookDetailNote(bookDetailId = detailA, note = "typo on page 4"))
        db.bookDetailNoteDao().insert(BookDetailNote(bookDetailId = detailA, note = "great ending"))

        val bookNoDetail = db.bookDao().insert(Book(title = "Book B"))

        // book WITH a detail: BookDetail is a 1:1 spine hop, not the terminal leaf - its own
        // notes (the next 1:N hop) must come through correctly.
        val result = db.getBookWithBookDetailThenBookDetailNoteList(bookWithNotes)
        assertNotNull(result)
        assertEquals(1, result!!.bookDetailNestedList.size)
        val detailWrapper = result.bookDetailNestedList.single()
        assertEquals("111-A", detailWrapper.bookDetail.isbn)
        assertEquals(setOf("typo on page 4", "great ending"), detailWrapper.bookDetailNoteList.map { it.note }.toSet())

        // book with NO detail row at all: the 1:1 hop's list must just be empty, not throw.
        val resultNoDetail = db.getBookWithBookDetailThenBookDetailNoteList(bookNoDetail)
        assertNotNull(resultNoDetail)
        assertTrue(resultNoDetail!!.bookDetailNestedList.isEmpty())

        db.close()
    }

    @Test
    fun `queryAuthorWithBookThenReviewListFlow filters a 3-level nested M+N chain by a hand-authored Where`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { LibraryDb_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val author = db.authorDao().insert(Author(name = "Ursula"))
        val book = db.bookDao().insert(Book(title = "Book A"))
        db.authorBookDao().insert(AuthorBook(authorId = author, bookId = book))

        // excluded by the WHERE below - must never leak into the results even though it has
        // its own book and review three hops deep, same shape as `author`'s.
        val otherAuthor = db.authorDao().insert(Author(name = "Someone Else"))
        val otherBook = db.bookDao().insert(Book(title = "Book B"))
        db.authorBookDao().insert(AuthorBook(authorId = otherAuthor, bookId = otherBook))
        db.reviewDao().insert(Review(bookId = otherBook, stars = 1))

        val flow = db.queryAuthorWithBookThenReviewListFlow(AuthorColumns.Id eq author)
        val firstEmitted = CompletableDeferred<Unit>()
        val resultsDeferred = async {
            flow.onEach { if (!firstEmitted.isCompleted) firstEmitted.complete(Unit) }.take(2).toList()
        }
        withTimeout(5.seconds) { firstEmitted.await() }

        // `review` is the deepest leaf table, three hops below `author` - and `otherAuthor`
        // is excluded by the WHERE, so it must never leak into the results.
        db.reviewDao().insert(Review(bookId = book, stars = 5))

        val results = withTimeout(10.seconds) { resultsDeferred.await() }
        assertEquals(2, results.size)
        assertEquals(listOf("Ursula"), results[0]!!.map { it.author.name })
        assertTrue(results[0]!!.single().bookNestedList.single().reviewList.isEmpty())
        assertEquals(listOf(5), results[1]!!.single().bookNestedList.single().reviewList.map { it.stars })

        db.close()
    }
}
