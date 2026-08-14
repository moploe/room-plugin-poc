package com.poc.basic

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase

@Entity(tableName = "basic_item")
data class BasicItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Dao
interface BasicDao {
    @Insert
    suspend fun insert(item: BasicItem): Long

    @Query("SELECT * FROM basic_item")
    suspend fun getAll(): List<BasicItem>
}

@Database(entities = [BasicItem::class], version = 1, exportSchema = false)
abstract class BasicDb : RoomDatabase() {
    abstract fun dao(): BasicDao
}
