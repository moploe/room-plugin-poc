package com.poc.ambiguousfk

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.RoomDatabase

/**
 * `Book` has TWO single-column foreign keys to `Author` (`authorId` and `editorId`). Relation
 * wrapper/function names are currently keyed only by the (parent, child) entity pair, not by
 * which FK column, so only the first FK encountered (`authorId`, declared first) gets a
 * generated relation - the second is skipped with an explicit ColumnsProcessor warning rather
 * than silently colliding or being dropped without a trace. This entity exists purely to prove
 * that behavior is real (see AmbiguousFkTest): the build must still succeed, and the relation
 * for the first FK must still work correctly.
 */
@Entity(tableName = "afk_author")
data class Author(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "afk_book",
    foreignKeys = [
        ForeignKey(entity = Author::class, parentColumns = ["id"], childColumns = ["authorId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Author::class, parentColumns = ["id"], childColumns = ["editorId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val authorId: Long,
    val editorId: Long? = null,
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

@Database(entities = [Author::class, Book::class], version = 1, exportSchema = false)
abstract class AmbiguousFkDb : RoomDatabase() {
    abstract fun authorDao(): AuthorDao
    abstract fun bookDao(): BookDao
}
