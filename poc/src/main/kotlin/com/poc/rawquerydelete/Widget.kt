package com.poc.rawquerydelete

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomDatabase
import androidx.room3.RoomRawQuery

@Entity(tableName = "widget")
data class Widget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Dao
interface WidgetDao {
    @Insert
    suspend fun insert(w: Widget): Long

    @Query("SELECT * FROM widget")
    suspend fun getAll(): List<Widget>

    // bare @RawQuery returning Int, for a DELETE - the minimal case, no generated
    // wrapper code involved at all, to isolate whether @RawQuery itself can do writes.
    @RawQuery
    suspend fun rawDelete(query: RoomRawQuery): Int
}

@Database(entities = [Widget::class], version = 1, exportSchema = false)
abstract class WidgetDb : RoomDatabase() {
    abstract fun dao(): WidgetDao
}
