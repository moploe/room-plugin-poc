package com.poc.nestedmn

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.RoomDatabase

// A small, self-contained entity graph whose only job is to prove a many-to-many hop can sit
// in the *middle* of a nested-relation chain, not just terminate one: Author -(M:N via
// AuthorBook)-> Book -(1:N)-> Review. Kept separate from the newtable/ migration-focused
// entities so this doesn't tangle with that schema's carefully-staged version history.

@Entity(tableName = "author")
data class Author(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(tableName = "book")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
)

@Entity(
    tableName = "author_book",
    primaryKeys = ["authorId", "bookId"],
    foreignKeys = [
        ForeignKey(entity = Author::class, parentColumns = ["id"], childColumns = ["authorId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class AuthorBook(val authorId: Long, val bookId: Long)

@Entity(
    tableName = "review",
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class Review(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val stars: Int,
)

// Book -(1:1)-> BookDetail -(1:N)-> BookDetailNote proves a 1:1 relation can ALSO sit in the
// middle of a nested chain, not just terminate one - same idea as the M:N-mid-chain proof
// above, for the other relation kind that used to be leaf-only.
@Entity(
    tableName = "book_detail",
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class BookDetail(
    @PrimaryKey val bookId: Long,
    val isbn: String,
)

@Entity(
    tableName = "book_detail_note",
    foreignKeys = [ForeignKey(entity = BookDetail::class, parentColumns = ["bookId"], childColumns = ["bookDetailId"], onDelete = ForeignKey.CASCADE)],
)
data class BookDetailNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookDetailId: Long,
    val note: String,
)

@Dao
interface AuthorDao {
    @Insert
    suspend fun insert(a: Author): Long
}

@Dao
interface BookDao {
    @Insert
    suspend fun insert(b: Book): Long
}

@Dao
interface AuthorBookDao {
    @Insert
    suspend fun insert(ab: AuthorBook): Long
}

@Dao
interface ReviewDao {
    @Insert
    suspend fun insert(r: Review): Long
}

@Dao
interface BookDetailDao {
    @Insert
    suspend fun insert(bd: BookDetail): Long
}

@Dao
interface BookDetailNoteDao {
    @Insert
    suspend fun insert(n: BookDetailNote): Long
}

@Database(
    entities = [Author::class, Book::class, AuthorBook::class, Review::class, BookDetail::class, BookDetailNote::class],
    version = 1,
    exportSchema = false,
)
abstract class LibraryDb : RoomDatabase() {
    abstract fun authorDao(): AuthorDao
    abstract fun bookDao(): BookDao
    abstract fun authorBookDao(): AuthorBookDao
    abstract fun reviewDao(): ReviewDao
    abstract fun bookDetailDao(): BookDetailDao
    abstract fun bookDetailNoteDao(): BookDetailNoteDao
}
