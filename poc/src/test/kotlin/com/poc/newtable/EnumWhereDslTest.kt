package com.poc.newtable

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.poc.plugin.runtime.eq
import com.poc.plugin.runtime.`in`
import com.poc.plugin.runtime.neq
import com.poc.plugin.runtime.set
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves the enum-typed eq/neq/in/set overloads (WhereDsl/SetDsl) let a caller compare and
 * assign an enum column directly against the enum constant - MemberColumns.Role is generated
 * as a plain StringColumn (Room's own enum<->TEXT mapping stores `.name`), so without these
 * overloads a caller would have to spell `.name` manually at every call site.
 */
class EnumWhereDslTest {

    private fun tempDbPath(): String {
        val f = File.createTempFile("enum-where-poc", ".db")
        f.delete()
        f.deleteOnExit()
        return f.absolutePath
    }

    private suspend fun seeded(): AccountDbV3 {
        val db = AccountDbV3Builder(tempDbPath()).setDriver(BundledSQLiteDriver()).build()
        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        db.memberDao().insert(Member(orgId = 1L, userId = accountId, role = Role.ADMIN, address = Address(city = "A", zip = "1")))
        db.memberDao().insert(Member(orgId = 2L, userId = accountId, role = Role.MEMBER, address = Address(city = "B", zip = "2")))
        db.memberDao().insert(Member(orgId = 3L, userId = accountId, role = Role.ADMIN, address = Address(city = "C", zip = "3")))
        return db
    }

    @Test
    fun `eq filters by enum constant, set assigns by enum constant`() = runBlocking {
        val db = seeded()
        val updated = db.updateMemberWhere(MemberColumns.Role eq Role.ADMIN, MemberColumns.Role set Role.MEMBER)
        assertEquals(2, updated)
        assertTrue(db.memberDao().getAll().all { it.role == Role.MEMBER })
        db.close()
    }

    @Test
    fun `neq filters by enum constant`() = runBlocking {
        val db = seeded()
        val deleted = db.deleteMemberWhere(MemberColumns.Role neq Role.ADMIN)
        assertEquals(1, deleted)
        val remaining = db.memberDao().getAll()
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.role == Role.ADMIN })
        db.close()
    }

    @Test
    fun `in filters by a collection of enum constants`() = runBlocking {
        val db = seeded()
        val deleted = db.deleteMemberWhere(MemberColumns.Role `in` listOf(Role.ADMIN))
        assertEquals(2, deleted)
        val remaining = db.memberDao().getAll()
        assertEquals(1, remaining.size)
        assertEquals(Role.MEMBER, remaining.single().role)
        db.close()
    }
}
