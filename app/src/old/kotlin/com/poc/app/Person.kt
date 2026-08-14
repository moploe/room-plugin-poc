package com.poc.app

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase

// This flavor represents the app BEFORE the schema change. PersonDbV2 does not
// exist anywhere in this build's classpath.

@Entity(tableName = "person")
data class PersonV1(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Dao
interface PersonDaoV1 {
    @Insert
    suspend fun insert(p: PersonV1): Long

    @Query("SELECT * FROM person")
    suspend fun getAll(): List<PersonV1>
}

@Database(entities = [PersonV1::class], version = 1, exportSchema = false)
abstract class PersonDbV1 : RoomDatabase() {
    abstract fun dao(): PersonDaoV1
}
