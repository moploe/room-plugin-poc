package com.poc.newtable

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the auto-generated one-to-many and one-to-one relation fetches actually work
 * end to end: insert real rows through the normal Dao layer, then fetch through the
 * generated get<Parent>With<Child>(id) function (which bypasses Room's Dao layer via
 * useReaderConnection, reconstructing rows with the generated read<Entity>() functions)
 * and confirm the assembled wrapper object is correct.
 */
class RelationsTest {

    @Test
    fun `one-to-many - account with its sessions`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV5_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1"))
        db.sessionDao().insert(SessionV2(accountId = accountId, token = "s2"))
        // a second, unrelated account to prove we don't leak its sessions into the first
        val otherAccountId = db.accountDao().insert(AccountV2(name = "other"))
        db.sessionDao().insert(SessionV2(accountId = otherAccountId, token = "s3"))

        val result = db.getAccountV2WithSessionV2List(accountId)
        assertNotNull(result)
        assertEquals("root", result!!.accountV2.name)
        assertEquals(setOf("s1", "s2"), result.sessionV2List.map { it.token }.toSet())

        val missing = db.getAccountV2WithSessionV2List(999_999L)
        assertNull(missing)

        db.close()
    }

    @Test
    fun `one-to-one - account with its profile, including the no-profile-yet case`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV5_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))

        // before a Profile exists: the wrapper's child side must be null, not throw
        val beforeProfile = db.getAccountV2WithProfile(accountId)
        assertNotNull(beforeProfile)
        assertNull(beforeProfile!!.profile)

        db.profileDao().insert(Profile(accountId = accountId, bio = "hello world"))
        val afterProfile = db.getAccountV2WithProfile(accountId)
        assertNotNull(afterProfile!!.profile)
        assertEquals("hello world", afterProfile.profile!!.bio)
        assertEquals(accountId, afterProfile.profile!!.accountId)

        db.close()
    }

    @Test
    fun `many-to-many - sessions and tags via the SessionTag junction table`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV6_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        val session1 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1"))
        val session2 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s2"))

        val tagMobile = db.tagDao().insert(Tag(label = "mobile"))
        val tagTrusted = db.tagDao().insert(Tag(label = "trusted"))
        val tagStale = db.tagDao().insert(Tag(label = "stale"))

        // session1 tagged mobile+trusted, session2 tagged only stale - proves the join
        // doesn't leak tags across sessions in either direction
        db.sessionTagDao().insert(SessionTag(sessionId = session1, tagId = tagMobile, pinned = true))
        db.sessionTagDao().insert(SessionTag(sessionId = session1, tagId = tagTrusted, pinned = false))
        db.sessionTagDao().insert(SessionTag(sessionId = session2, tagId = tagStale))

        val session1Tags = db.getSessionV2WithTagViaSessionTag(session1)
        assertNotNull(session1Tags)
        assertEquals(setOf("mobile", "trusted"), session1Tags!!.tagWithJunctionList.map { it.first.label }.toSet())
        // the junction row paired with each tag must actually belong to session1, not just
        // any row that happens to reference that tag - proves the id->entity map join is correct
        session1Tags.tagWithJunctionList.forEach { (tag, junction) ->
            assertEquals(session1, junction.sessionId)
            assertEquals(tag.id, junction.tagId)
        }
        // the non-FK "pinned" column must survive the join, proving junction metadata (not
        // just the FK used to match rows) is exposed rather than discarded
        assertEquals(true, session1Tags.tagWithJunctionList.first { it.first.label == "mobile" }.second.pinned)
        assertEquals(false, session1Tags.tagWithJunctionList.first { it.first.label == "trusted" }.second.pinned)

        val session2Tags = db.getSessionV2WithTagViaSessionTag(session2)
        assertEquals(setOf("stale"), session2Tags!!.tagWithJunctionList.map { it.first.label }.toSet())
        assertEquals(session2, session2Tags.tagWithJunctionList.single().second.sessionId)

        // a session with zero tags: must return an empty list, not throw or return null tags
        val session3 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s3"))
        val session3Tags = db.getSessionV2WithTagViaSessionTag(session3)
        assertNotNull(session3Tags)
        assertTrue(session3Tags!!.tagWithJunctionList.isEmpty())

        db.close()
    }

    @Test
    fun `batch fetch - getAllXWithYList fetches multiple parents in 2 queries instead of N`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV6_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val a1 = db.accountDao().insert(AccountV2(name = "a1"))
        val a2 = db.accountDao().insert(AccountV2(name = "a2"))
        val a3 = db.accountDao().insert(AccountV2(name = "a3")) // zero sessions - must still appear
        db.sessionDao().insert(SessionV2(accountId = a1, token = "a1-s1"))
        db.sessionDao().insert(SessionV2(accountId = a1, token = "a1-s2"))
        db.sessionDao().insert(SessionV2(accountId = a2, token = "a2-s1"))

        // empty id list: must short-circuit to an empty result, not query with an empty IN(...)
        assertTrue(db.getAllAccountV2WithSessionV2List(emptyList()).isEmpty())

        val batch = db.getAllAccountV2WithSessionV2List(listOf(a1, a2, a3))
        assertEquals(3, batch.size)
        val byName = batch.associateBy { it.accountV2.name }
        assertEquals(setOf("a1-s1", "a1-s2"), byName.getValue("a1").sessionV2List.map { it.token }.toSet())
        assertEquals(setOf("a2-s1"), byName.getValue("a2").sessionV2List.map { it.token }.toSet())
        assertTrue(byName.getValue("a3").sessionV2List.isEmpty())

        // must agree with the single-id fetch for every parent
        for (id in listOf(a1, a2, a3)) {
            val single = db.getAccountV2WithSessionV2List(id)!!
            val fromBatch = batch.first { it.accountV2.id == id }
            assertEquals(single.sessionV2List.map { it.token }.toSet(), fromBatch.sessionV2List.map { it.token }.toSet())
        }

        db.close()
    }

    @Test
    fun `batch fetch - getAllXWithProfile (one-to-one) handles missing children`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV6_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val withProfile = db.accountDao().insert(AccountV2(name = "has-profile"))
        val withoutProfile = db.accountDao().insert(AccountV2(name = "no-profile"))
        db.profileDao().insert(Profile(accountId = withProfile, bio = "bio text"))

        val batch = db.getAllAccountV2WithProfile(listOf(withProfile, withoutProfile))
        assertEquals(2, batch.size)
        val byName = batch.associateBy { it.accountV2.name }
        assertEquals("bio text", byName.getValue("has-profile").profile?.bio)
        assertNull(byName.getValue("no-profile").profile)

        db.close()
    }

    @Test
    fun `batch fetch - getAllXWithYViaJunction (many-to-many) matches single fetch and keeps junction metadata`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV6_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        val session1 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1"))
        val session2 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s2"))
        val session3 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s3")) // no tags

        val tagMobile = db.tagDao().insert(Tag(label = "mobile"))
        val tagTrusted = db.tagDao().insert(Tag(label = "trusted"))
        db.sessionTagDao().insert(SessionTag(sessionId = session1, tagId = tagMobile, pinned = true))
        db.sessionTagDao().insert(SessionTag(sessionId = session2, tagId = tagTrusted, pinned = false))

        val batch = db.getAllSessionV2WithTagViaSessionTag(listOf(session1, session2, session3))
        assertEquals(3, batch.size)
        val byToken = batch.associateBy { it.sessionV2.token }
        assertEquals(listOf("mobile" to true), byToken.getValue("s1").tagWithJunctionList.map { it.first.label to it.second.pinned })
        assertEquals(listOf("trusted" to false), byToken.getValue("s2").tagWithJunctionList.map { it.first.label to it.second.pinned })
        assertTrue(byToken.getValue("s3").tagWithJunctionList.isEmpty())

        db.close()
    }

    @Test
    fun `two-level nested relation - account with sessions, each with its own tags`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV6_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        val session1 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1"))
        val session2 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s2"))
        // a session with no tags at all - must still appear, with an empty nested tag list
        val session3 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s3"))
        // an unrelated account, to prove its sessions/tags don't leak into the nested fetch
        val otherAccountId = db.accountDao().insert(AccountV2(name = "other"))
        val otherSession = db.sessionDao().insert(SessionV2(accountId = otherAccountId, token = "other-s"))

        val tagMobile = db.tagDao().insert(Tag(label = "mobile"))
        val tagTrusted = db.tagDao().insert(Tag(label = "trusted"))
        db.sessionTagDao().insert(SessionTag(sessionId = session1, tagId = tagMobile, pinned = true))
        db.sessionTagDao().insert(SessionTag(sessionId = session2, tagId = tagTrusted, pinned = false))
        db.sessionTagDao().insert(SessionTag(sessionId = otherSession, tagId = tagMobile))

        val nested = db.getAccountV2WithSessionV2ThenTagViaSessionTag(accountId)
        assertNotNull(nested)
        assertEquals("root", nested!!.accountV2.name)
        assertEquals(3, nested.sessionV2NestedList.size) // s1, s2, s3 - not otherSession

        val byToken = nested.sessionV2NestedList.associateBy { it.sessionV2.token }
        assertEquals(listOf("mobile"), byToken.getValue("s1").tagWithJunctionList.map { it.first.label })
        assertEquals(listOf("trusted"), byToken.getValue("s2").tagWithJunctionList.map { it.first.label })
        assertTrue(byToken.getValue("s3").tagWithJunctionList.isEmpty())
        // otherSession's tag must not leak into this account's nested fetch anywhere
        assertTrue(byToken.values.none { it.sessionV2.id == otherSession })

        // an account with zero sessions: the top-level parent still resolves, nested list is empty
        val emptyAccount = db.accountDao().insert(AccountV2(name = "empty"))
        val nestedEmpty = db.getAccountV2WithSessionV2ThenTagViaSessionTag(emptyAccount)
        assertNotNull(nestedEmpty)
        assertTrue(nestedEmpty!!.sessionV2NestedList.isEmpty())

        // a nonexistent account: the whole nested fetch resolves to null
        assertNull(db.getAccountV2WithSessionV2ThenTagViaSessionTag(999_999L))

        db.close()
    }

    @Test
    fun `three-level nested relation - account, then each session's own children, then each child's tags`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV6_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        val session1 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1"))
        val childA = db.sessionDao().insert(SessionV2(accountId = accountId, token = "child-a", parentSessionId = session1))
        val childB = db.sessionDao().insert(SessionV2(accountId = accountId, token = "child-b", parentSessionId = session1))
        val tagUrgent = db.tagDao().insert(Tag(label = "urgent"))
        db.sessionTagDao().insert(SessionTag(sessionId = childA, tagId = tagUrgent, pinned = true))
        // childB and session1 itself deliberately get no tags, to also prove empty leaf lists.

        // an unrelated account+session+child+tag chain, to prove no cross-account leakage
        // through all three levels at once.
        val otherAccountId = db.accountDao().insert(AccountV2(name = "other"))
        val otherSession = db.sessionDao().insert(SessionV2(accountId = otherAccountId, token = "other-s"))
        val otherChild = db.sessionDao().insert(SessionV2(accountId = otherAccountId, token = "other-child", parentSessionId = otherSession))
        val otherTag = db.tagDao().insert(Tag(label = "other-tag"))
        db.sessionTagDao().insert(SessionTag(sessionId = otherChild, tagId = otherTag))

        val result = db.getAccountV2WithSessionV2ThenSessionV2ThenTagViaSessionTag(accountId)
        assertNotNull(result)
        assertEquals("root", result!!.accountV2.name)
        // account -> session is flat by accountId, so all 3 of this account's sessions show up
        // at the outer level (session1, childA, childB) - each then carries its OWN children
        // (via the self-FK) at the next level down.
        assertEquals(3, result.sessionV2NestedList.size)
        assertTrue(result.sessionV2NestedList.none { it.sessionV2.id == otherSession || it.sessionV2.id == otherChild })

        val byToken = result.sessionV2NestedList.associateBy { it.sessionV2.token }

        // session1's own children (childA, childB) appear one level deeper, each already
        // carrying its own tags via the depth-1 many-to-many relation reused underneath.
        val session1Nested = byToken.getValue("s1").sessionV2NestedList.associateBy { it.sessionV2.token }
        assertEquals(setOf("child-a", "child-b"), session1Nested.keys)
        assertEquals(listOf("urgent"), session1Nested.getValue("child-a").tagWithJunctionList.map { it.first.label })
        assertTrue(session1Nested.getValue("child-b").tagWithJunctionList.isEmpty())

        // childA/childB have no children of their own (nothing references them as a parent).
        assertTrue(byToken.getValue("child-a").sessionV2NestedList.isEmpty())
        assertTrue(byToken.getValue("child-b").sessionV2NestedList.isEmpty())

        // the batch (ids-based) version must agree with the single-id version, across
        // multiple unrelated accounts in one call.
        val batch = db.getAllAccountV2WithSessionV2ThenSessionV2ThenTagViaSessionTag(listOf(accountId, otherAccountId))
        assertEquals(2, batch.size)
        val rootFromBatch = batch.first { it.accountV2.name == "root" }
        assertEquals(result.sessionV2NestedList.map { it.sessionV2.token }.toSet(), rootFromBatch.sessionV2NestedList.map { it.sessionV2.token }.toSet())
        val otherFromBatch = batch.first { it.accountV2.name == "other" }
        assertEquals(setOf("other-s", "other-child"), otherFromBatch.sessionV2NestedList.map { it.sessionV2.token }.toSet())

        // a nonexistent account resolves to null, same as every shallower level already does.
        assertNull(db.getAccountV2WithSessionV2ThenSessionV2ThenTagViaSessionTag(999_999L))

        db.close()
    }

    @Test
    fun `Member (uses Embedded) now gets full relation support, reconstructing its nested Address correctly`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder { AccountDbV6_Impl() }
            .setDriver(BundledSQLiteDriver())
            .build()

        val accountId = db.accountDao().insert(AccountV2(name = "root"))
        db.memberDao().insert(Member(orgId = 1, userId = accountId, role = Role.ADMIN, address = Address("Springfield", "00000")))
        db.memberDao().insert(Member(orgId = 2, userId = accountId, role = Role.MEMBER, address = Address("Shelbyville", "11111")))
        val otherAccountId = db.accountDao().insert(AccountV2(name = "other"))
        db.memberDao().insert(Member(orgId = 3, userId = otherAccountId, role = Role.ADMIN, address = Address("Capital City", "22222")))

        val result = db.getAccountV2WithMemberList(accountId)
        assertNotNull(result)
        assertEquals(2, result!!.memberList.size)
        val byOrg = result.memberList.associateBy { it.orgId }
        assertEquals(Role.ADMIN, byOrg.getValue(1).role)
        assertEquals(Address("Springfield", "00000"), byOrg.getValue(1).address)
        assertEquals(Role.MEMBER, byOrg.getValue(2).role)
        assertEquals(Address("Shelbyville", "11111"), byOrg.getValue(2).address)
        // otherAccountId's member must not leak in
        assertTrue(result.memberList.none { it.orgId == 3L })

        // the batch (ids-based) version must reconstruct the same embedded Address values.
        val batch = db.getAllAccountV2WithMemberList(listOf(accountId, otherAccountId))
        assertEquals(2, batch.size)
        val otherResult = batch.first { it.accountV2.name == "other" }
        assertEquals(listOf(Address("Capital City", "22222")), otherResult.memberList.map { it.address })

        db.close()
    }
}
