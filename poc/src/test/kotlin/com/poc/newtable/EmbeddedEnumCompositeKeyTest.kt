package com.poc.newtable

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Exercises @Embedded flattening + enum-as-TEXT + composite primary key together, none of
 * which any other test entity touches - and does it by jumping straight from v1 to v3
 * (skipping v2 entirely) to prove the "any earlier version upgrades in one step, no
 * chained intermediate migrations" claim is real, not just plausible-looking codegen.
 */
class EmbeddedEnumCompositeKeyTest {

    private fun tempDbPath(): String {
        val f = File.createTempFile("embedded-enum-poc", ".db")
        f.delete()
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test
    fun `embedded fields, enum columns and composite primary keys survive a version-skipping migration`() = runBlocking {
        val path = tempDbPath()

        val v1 = AccountDbV1Builder(path).setDriver(BundledSQLiteDriver()).build()
        v1.dao().insert(AccountV1(name = "root"))
        v1.close()

        // jump straight from v1 to v3 - v2 (Session) never existed in this file's history,
        // only Migration(1, 3) applies, and it still has to create both Session and Member.
        val v3 = AccountDbV3Builder(path).setDriver(BundledSQLiteDriver()).build()

        val accountId = v3.accountDao().getAll().single().id

        v3.memberDao().insert(
            Member(
                orgId = 1L,
                userId = accountId,
                role = Role.ADMIN,
                address = Address(city = "Springfield", zip = "00000"),
            ),
        )
        val members = v3.memberDao().getAll()
        assertEquals(1, members.size)
        assertEquals(Role.ADMIN, members[0].role)
        assertEquals("Springfield", members[0].address.city)
        assertEquals("00000", members[0].address.zip)

        v3.sessionDao().insert(Session(accountId = accountId, token = "tok-xyz"))
        assertEquals(1, v3.sessionDao().getAll().size)

        v3.close()
    }
}
