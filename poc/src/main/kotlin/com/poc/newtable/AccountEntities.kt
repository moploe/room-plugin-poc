package com.poc.newtable

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

// ---- v1: "code before the change" - just Account, no Session table exists yet ----

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

// ---- v2: "code after the change" - Account gets a new column AND a whole new Session
// entity/table was added. This is the exact scenario that used to crash: the old code
// only knew how to ALTER TABLE ADD COLUMN, and PRAGMA table_info on a table that doesn't
// exist yet silently returns nothing, so it tried "ALTER TABLE session ADD COLUMN ..."
// against a table that was never created. ----

@Entity(tableName = "account")
data class AccountV2(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "active", defaultValue = "1")
    val active: Boolean = true,
)

@Entity(
    tableName = "session",
    indices = [Index(value = ["token"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = AccountV2::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val token: String,
)

@Dao
interface AccountDaoV2 {
    @Insert
    suspend fun insert(a: AccountV2): Long

    @Query("SELECT * FROM account")
    suspend fun getAll(): List<AccountV2>
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(s: Session): Long

    @Query("SELECT * FROM session")
    suspend fun getAll(): List<Session>
}

// ---- Member: exercises @Embedded flattening + enum-as-TEXT + composite primary key,
// none of which any other test entity in this project touches. Added as a brand-new
// entity in v3 (on top of v2's Account+Session) so it goes through the same migration
// path as Session did - a pure fresh-install test wouldn't touch our generated
// CreateTableSql/ExpectedColumns at all, since Room's own native onCreate path handles
// fresh installs, not ours. ----

enum class Role { ADMIN, MEMBER }

data class Address(val city: String, val zip: String)

@Entity(
    tableName = "member",
    primaryKeys = ["orgId", "userId"],
    foreignKeys = [ForeignKey(entity = AccountV2::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE)],
)
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

@Database(entities = [AccountV2::class, Session::class], version = 2, exportSchema = false)
abstract class AccountDbV2 : RoomDatabase() {
    abstract fun accountDao(): AccountDaoV2
    abstract fun sessionDao(): SessionDao
}

@Database(entities = [AccountV2::class, Session::class, Member::class], version = 3, exportSchema = false)
abstract class AccountDbV3 : RoomDatabase() {
    abstract fun accountDao(): AccountDaoV2
    abstract fun sessionDao(): SessionDao
    abstract fun memberDao(): MemberDao
}

// ---- v4: `session` (which already exists from v2) gains a nullable, self-referencing FK
// column. This is the "successful" branch of the FK-via-ALTER fix - unlike accountId
// (NOT NULL, warned-and-dropped), parentSessionId is nullable with no other default, so
// SQLite allows ALTER TABLE ADD COLUMN with a REFERENCES clause for it. ----

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
interface SessionDaoV2 {
    @Insert
    suspend fun insert(s: SessionV2): Long

    @RawQuery
    suspend fun query(query: RoomRawQuery): List<SessionV2>

    // No delete-by-Where method declared here on purpose: it doesn't go through the Dao
    // at all, see the generated AccountDbV4.deleteSessionV2Where(where) instead - @RawQuery
    // can't reliably do writes in Room 3 (see processDao's doc comment for why).
}

@Database(entities = [AccountV2::class, SessionV2::class, Member::class], version = 4, exportSchema = false)
abstract class AccountDbV4 : RoomDatabase() {
    abstract fun accountDao(): AccountDaoV2
    abstract fun sessionDao(): SessionDaoV2
    abstract fun memberDao(): MemberDao
}

// ---- Profile: one-to-one with Account via a shared primary key (accountId is both
// Profile's own PK AND its FK to account) - the classic one-to-one pattern, and the
// specific shape autoOneToOne detection looks for (FK child column == the whole PK). ----

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

@Database(entities = [AccountV2::class, SessionV2::class, Member::class, Profile::class], version = 5, exportSchema = false)
abstract class AccountDbV5 : RoomDatabase() {
    abstract fun accountDao(): AccountDaoV2
    abstract fun sessionDao(): SessionDaoV2
    abstract fun memberDao(): MemberDao
    abstract fun profileDao(): ProfileDao
}

// ---- Tag / SessionTag: many-to-many between SessionV2 and Tag via a junction table.
// SessionTag is "junction-shaped": composite 2-column PK, each column its own single-column
// FK to a different entity - that's the whole detection signature, no new annotation needed. ----

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
