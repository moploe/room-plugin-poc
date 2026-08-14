package com.poc.app

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomDatabase
import androidx.room3.RoomRawQuery

// Device-verification version of the same new-table/FK-ALTER/embedded/enum/orderBy/delete
// scenarios already exhaustively covered by :poc's JVM tests - this exists so those same
// mechanisms can be tapped through on a real device with AndroidSQLiteDriver, not just
// proven on the JVM with BundledSQLiteDriver.

@Entity(tableName = "account")
data class AccountV1(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Dao
interface AccountDaoV1 {
    @Insert
    suspend fun insert(a: AccountV1): Long

    @Query("SELECT * FROM account")
    suspend fun getAll(): List<AccountV1>
}

@Database(entities = [AccountV1::class], version = 1, exportSchema = false)
abstract class AccountDbV1 : RoomDatabase() {
    abstract fun dao(): AccountDaoV1
}

@Entity(tableName = "account")
data class AccountV2(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "active", defaultValue = "1")
    val active: Boolean = true,
)

enum class Role { ADMIN, MEMBER }

data class Address(val city: String, val zip: String)

@Entity(tableName = "member", primaryKeys = ["orgId", "userId"])
data class Member(
    val orgId: Long,
    val userId: Long,
    val role: Role,
    @Embedded(prefix = "addr_")
    val address: Address,
)

@Dao
interface MemberDao {
    @Insert
    suspend fun insert(m: Member): Long

    @Query("SELECT * FROM member")
    suspend fun getAll(): List<Member>
}

@Entity(
    tableName = "session",
    indices = [Index(value = ["token"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = AccountV2::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SessionV2::class, parentColumns = ["id"], childColumns = ["parentSessionId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class SessionV2(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val token: String,
    val parentSessionId: Long? = null,
)

@Dao
interface AccountDaoV2 {
    @Insert
    suspend fun insert(a: AccountV2): Long

    @Query("SELECT * FROM account")
    suspend fun getAll(): List<AccountV2>
}

@Dao
interface SessionDaoV2 {
    @Insert
    suspend fun insert(s: SessionV2): Long

    @RawQuery
    suspend fun query(query: RoomRawQuery): List<SessionV2>
}

// A brand-new entity (Session) AND a nullable self-FK column ADDed to an existing table
// both get exercised by jumping straight from v1 to v4.
@Database(entities = [AccountV2::class, SessionV2::class, Member::class], version = 4, exportSchema = false)
abstract class AccountDbV4 : RoomDatabase() {
    abstract fun accountDao(): AccountDaoV2
    abstract fun sessionDao(): SessionDaoV2
    abstract fun memberDao(): MemberDao
}

// ---- Profile: one-to-one with Account via a shared primary key. ----
@Entity(
    tableName = "profile",
    foreignKeys = [ForeignKey(entity = AccountV2::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
)
data class Profile(
    @PrimaryKey val accountId: Long,
    val bio: String,
)

@Dao
interface ProfileDao {
    @Insert
    suspend fun insert(p: Profile): Long
}

// ---- Tag / SessionTag: many-to-many between SessionV2 and Tag via a composite-PK,
// both-columns-are-FKs junction table. ----
@Entity(tableName = "tag")
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
)

@Dao
interface TagDao {
    @Insert
    suspend fun insert(t: Tag): Long
}

@Entity(
    tableName = "session_tag",
    primaryKeys = ["sessionId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = SessionV2::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SessionTag(
    val sessionId: Long,
    val tagId: Long,
    // a non-FK junction column - proves many-to-many fetches expose junction metadata
    // instead of discarding everything but the FK used to join.
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,
)

@Dao
interface SessionTagDao {
    @Insert
    suspend fun insert(st: SessionTag): Long
}

@Database(
    entities = [AccountV2::class, SessionV2::class, Member::class, Profile::class, Tag::class, SessionTag::class],
    version = 6,
    exportSchema = false,
)
abstract class AccountDbV6 : RoomDatabase() {
    abstract fun accountDao(): AccountDaoV2
    abstract fun sessionDao(): SessionDaoV2
    abstract fun memberDao(): MemberDao
    abstract fun profileDao(): ProfileDao
    abstract fun tagDao(): TagDao
    abstract fun sessionTagDao(): SessionTagDao
}
