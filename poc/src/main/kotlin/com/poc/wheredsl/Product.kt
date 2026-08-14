package com.poc.wheredsl

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.RawQuery
import androidx.room3.RoomDatabase
import androidx.room3.RoomRawQuery

@Entity(tableName = "product")
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: Double,
)

// ProductColumns is generated from the @Entity above.
// ProductDao.query(where: Where) is generated from the @RawQuery method below - see
// build/generated/ksp/main/kotlin/com/poc/wheredsl/ProductDaoWhereQueries.kt after building.

@Dao
interface ProductDao {
    @Insert
    suspend fun insert(p: Product): Long

    @RawQuery
    suspend fun query(query: RoomRawQuery): List<Product>
}

@Database(entities = [Product::class], version = 1, exportSchema = false)
abstract class ProductDb : RoomDatabase() {
    abstract fun dao(): ProductDao
}
