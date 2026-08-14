package com.poc.ignoretest

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Ignore
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase

/**
 * `computedLabel` is @Ignore'd - it must never become a real column (CREATE TABLE, generated
 * ExpectedColumns, or a named arg read from a SELECT * row in the generated readIgnoreDemo()),
 * unlike every other property here.
 */
@Entity(tableName = "ignore_demo")
data class IgnoreDemo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @Ignore val computedLabel: String = "unset",
)

@Dao
interface IgnoreDemoDao {
    @Insert
    suspend fun insert(e: IgnoreDemo): Long

    @Query("SELECT * FROM ignore_demo")
    suspend fun getAll(): List<IgnoreDemo>
}

@Database(entities = [IgnoreDemo::class], version = 1, exportSchema = false)
abstract class IgnoreDemoDb : RoomDatabase() {
    abstract fun dao(): IgnoreDemoDao
}
