package com.poc.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.poc.plugin.runtime.asc
import com.poc.plugin.runtime.eq
import com.poc.plugin.runtime.gt
import com.poc.plugin.runtime.isNotNull
import com.poc.plugin.runtime.plus
import com.poc.plugin.runtime.set
import com.poc.plugin.runtime.setNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Databases {
    private var product: ProductDb? = null
    private var order: OrderDb? = null

    private fun pathFor(context: Context, name: String) =
        context.getDatabasePath(name).also { it.parentFile?.mkdirs() }.absolutePath

    fun product(context: Context): ProductDb = product ?: ProductDbBuilder(pathFor(context, "product.db"))
        .setDriver(AndroidSQLiteDriver()).build().also { product = it }

    fun order(context: Context): OrderDb = order ?: OrderDbBuilder(pathFor(context, "orders.db"))
        .setDriver(AndroidSQLiteDriver()).build().also { order = it }

    // buttons ④⑤⑥⑦ each insert hardcoded rows (tokens under session.token's unique index)
    // into their own dedicated db file - re-tapping the same button without wiping that
    // file first would hit the unique constraint on the second run and crash. Deleting the
    // db (+ its WAL/SHM/journal siblings) before every tap makes each button repeatable.
    fun freshDbPath(context: Context, name: String): String {
        val dbFile = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            java.io.File(dbFile.parentFile, dbFile.name + suffix).delete()
        }
        return dbFile.absolutePath
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

@Composable
fun Screen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf(listOf<String>()) }

    fun push(line: String) { log = listOf(line) + log }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "NEW build (v2) — 这个 APK 里根本没有 PersonDbV1 这个类。\n" +
                "如果这是覆盖安装在装过 OLD 包的设备上，person.db 文件是 OLD 留下来的，\n" +
                "打开它会真实触发 Migration(1,2)。",
            style = MaterialTheme.typography.titleMedium,
        )

        Button(onClick = {
            scope.launch {
                val dbPath = context.getDatabasePath("person.db").also { it.parentFile?.mkdirs() }.absolutePath
                val dao = PersonDbV2Builder(dbPath)
                    .setDriver(AndroidSQLiteDriver())
                    .build()
                    .dao()
                val id = dao.insert(PersonV2(name = "Bob", age = 30))
                val all = dao.getAll()
                push("[NEW v2] opened person.db (real migration if it pre-existed), inserted Bob, id=$id, rows=${all.map { "${it.name}:${it.age}" }}")
            }
        }) { Text("① 打开 person.db 为 v2 (真实迁移路径) + 插入 Bob") }

        Button(onClick = {
            scope.launch {
                val dao = Databases.product(context).dao()
                dao.insert(Product(name = "Widget", price = 5.0))
                dao.insert(Product(name = "Gadget", price = 15.0))
                dao.insert(Product(name = "Gizmo", price = 25.0))
                val expensive = dao.query(ProductColumns.Price gt 10.0)
                push("② product.db: inserted 3 rows, Where DSL (price>10) matched: ${expensive.map { it.name }}")
            }
        }) { Text("② 插入商品 + 跑 Where DSL 查询") }

        Button(onClick = {
            scope.launch {
                val dao = Databases.order(context).dao()
                val id1 = dao.insert(OrderEntity(shipping = Shipping("1 Infinite Loop", "95014")))
                val id2 = dao.insertDefault(OrderEntityDefault(shipping = Shipping("221B Baker Street", "NW16XE")))
                push("③ orders.db: custom-converter row id=$id1 (should be 'CUSTOM:' in DB), default-converter row id=$id2 (should be plain JSON)")
                // read the JSON text back through kotlinx.serialization decode (not just check
                // the raw stored text) - this is the part that would actually break under R8
                // if the default converter's generated serializer got obfuscated/stripped.
                val readBack = dao.getAllDefault().last().shipping
                push("③ read-back through default JSON converter: address='${readBack.address}', zip='${readBack.zip}' (should match what was inserted)")
            }
        }) { Text("③ 插入订单 (自定义 vs 默认 JSON 转换器)") }

        Button(onClick = {
            scope.launch {
                val dbPath = Databases.freshDbPath(context, "account.db")

                // v1 -> v4 in one step: exercises brand-new-table creation (session, member -
                // neither existed at v1) with their FK/index/embedded/enum/composite-PK all
                // baked into the generated CREATE TABLE.
                val v1 = AccountDbV1Builder(dbPath).setDriver(AndroidSQLiteDriver()).build()
                val accountId = v1.dao().insert(AccountV1(name = "root"))
                v1.close()

                val v4 = AccountDbV4Builder(dbPath).setDriver(AndroidSQLiteDriver()).build()
                val parent = v4.sessionDao().insert(SessionV2(accountId = accountId, token = "root-session"))
                v4.sessionDao().insert(SessionV2(accountId = accountId, token = "child-a", parentSessionId = parent))
                v4.sessionDao().insert(SessionV2(accountId = accountId, token = "child-b", parentSessionId = parent))
                v4.memberDao().insert(Member(orgId = 1, userId = accountId, role = Role.ADMIN, address = Address("Springfield", "00000")))

                // orderBy + limit
                val ordered = v4.sessionDao().query(
                    SessionV2Columns.AccountId eq accountId,
                    orderBy = SessionV2Columns.Token.asc(),
                    limit = 2,
                )

                // delete-by-Where via useWriterConnection (bypasses @RawQuery entirely,
                // since @RawQuery can't reliably write in Room 3 - confirmed empirically)
                val deleted = v4.deleteSessionV2Where(SessionV2Columns.ParentSessionId.isNotNull)
                val remaining = v4.sessionDao().query(SessionV2Columns.AccountId eq accountId)

                push(
                    "④ account.db (v1→v4 直接跳版本): sessions ordered+limit(2)=${ordered.map { it.token }}, " +
                        "member=${v4.memberDao().getAll().map { "${it.role}@${it.address.city}" }}, " +
                        "deleteSessionV2Where 删了 $deleted 条, 剩余=${remaining.map { it.token }}",
                )
            }
        }) { Text("④ 新建表+FK+索引+Embedded+枚举 / orderBy+limit / delete-by-Where") }

        Button(onClick = {
            scope.launch {
                val dbPath = Databases.freshDbPath(context, "relations.db")
                val db = AccountDbV6Builder(dbPath).setDriver(AndroidSQLiteDriver()).build()

                val accountId = db.accountDao().insert(AccountV2(name = "root"))
                val session1 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1"))
                db.sessionDao().insert(SessionV2(accountId = accountId, token = "s2"))
                // self-referencing FK: child sessions spawned from session1
                val childA = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1-child-a", parentSessionId = session1))
                db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1-child-b", parentSessionId = session1))
                db.profileDao().insert(Profile(accountId = accountId, bio = "hello"))
                val tagMobile = db.tagDao().insert(Tag(label = "mobile"))
                val tagTrusted = db.tagDao().insert(Tag(label = "trusted"))
                db.sessionTagDao().insert(SessionTag(sessionId = session1, tagId = tagMobile, pinned = true))
                db.sessionTagDao().insert(SessionTag(sessionId = session1, tagId = tagTrusted, pinned = false))
                // tag one of session1's CHILDREN too, so the 3-level nested demo below has
                // something non-empty to show at the deepest leaf (child-a's own tags).
                db.sessionTagDao().insert(SessionTag(sessionId = childA, tagId = tagMobile, pinned = false))

                // a second account, used only to prove batch fetch below returns both
                // accounts from 2 queries instead of looping the single-id fetch N times
                val accountId2 = db.accountDao().insert(AccountV2(name = "root2"))
                db.sessionDao().insert(SessionV2(accountId = accountId2, token = "r2-s1"))

                // one-to-many: account -> its sessions
                val withSessions = db.getAccountV2WithSessionV2List(accountId)
                // one-to-one: account -> its profile (shared-PK pattern)
                val withProfile = db.getAccountV2WithProfile(accountId)
                // many-to-many: session -> its tags + junction metadata, via SessionTag
                val withTags = db.getSessionV2WithTagViaSessionTag(session1)
                // self-referencing one-to-many: session1 -> its child sessions
                val withChildren = db.getSessionV2WithSessionV2List(session1)
                // batch fetch: both accounts' sessions in one 2-query round trip
                val batchSessions = db.getAllAccountV2WithSessionV2List(listOf(accountId, accountId2))
                // two-level nested: account -> its sessions -> each session's own tags,
                // in 3 total queries (parent, child-id list, batched grandchild fetch) -
                // reuses getAllSessionV2WithTagViaSessionTag from button ⑤'s batch case above.
                val nested = db.getAccountV2WithSessionV2ThenTagViaSessionTag(accountId)
                // three-level nested: account -> its sessions -> each session's own children ->
                // each of THOSE children's own tags. Still a fixed number of queries total
                // regardless of how many sessions/children/tags exist, since every level just
                // calls the (already-generated) level-below batch fetch once for the union of
                // all ids at that level - never once per row.
                val nested3 = db.getAccountV2WithSessionV2ThenSessionV2ThenTagViaSessionTag(accountId)

                push(
                    "⑤ relations.db: 一对多 sessions=${withSessions?.sessionV2List?.map { it.token }}, " +
                        "一对一 profile=${withProfile?.profile?.bio}, " +
                        "多对多 session1 的 tags(含中间表pinned字段)=${withTags?.tagWithJunctionList?.map { (t, j) -> "${t.label}:pinned=${j.pinned}" }}, " +
                        "自引用 session1 的子session=${withChildren?.sessionV2List?.map { it.token }}, " +
                        "批量拉取(getAllAccountV2WithSessionV2List)=${batchSessions.associate { it.accountV2.name to it.sessionV2List.map { s -> s.token } }}, " +
                        "两层嵌套(account→sessions→each session's tags)=${nested?.sessionV2NestedList?.associate { it.sessionV2.token to it.tagWithJunctionList.map { p -> p.first.label } }}, " +
                        "三层嵌套(account→sessions→each session's children→each child's tags)=${nested3?.sessionV2NestedList?.associate { s -> s.sessionV2.token to s.sessionV2NestedList.associate { c -> c.sessionV2.token to c.tagWithJunctionList.map { p -> p.first.label } } }}",
                )
            }
        }) { Text("⑤ 一对多/一对一/多对多 关系自动生成验证") }

        Button(onClick = {
            scope.launch {
                val dbPath = Databases.freshDbPath(context, "updatewhere.db")
                val db = AccountDbV4Builder(dbPath).setDriver(AndroidSQLiteDriver()).build()

                val accountId = db.accountDao().insert(AccountV2(name = "root"))
                val other = db.accountDao().insert(AccountV2(name = "other"))
                val s1 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s1"))
                val s2 = db.sessionDao().insert(SessionV2(accountId = accountId, token = "s2"))
                val untouched = db.sessionDao().insert(SessionV2(accountId = other, token = "untouched"))

                // single-column SET: move both of accountId's sessions to `other`
                val moved = db.updateSessionV2Where(SessionV2Columns.AccountId eq accountId, SessionV2Columns.AccountId set other)
                // multi-column SET (combined via +): rename s1 and give it a parent, in one UPDATE
                val renamed = db.updateSessionV2Where(
                    SessionV2Columns.Id eq s1,
                    (SessionV2Columns.Token set "renamed") + (SessionV2Columns.ParentSessionId set s2),
                )
                // setNull: clear the FK we just set
                val cleared = db.updateSessionV2Where(SessionV2Columns.Id eq s1, SessionV2Columns.ParentSessionId.setNull())

                val finalRows = db.sessionDao().query(SessionV2Columns.AccountId eq other)

                push(
                    "⑥ updatewhere.db: 单列SET移动了 $moved 条到 other 账号, " +
                        "多列SET(token+parentSessionId)影响了 $renamed 条, " +
                        "setNull清空了 $cleared 条, " +
                        "最终 other 账号下的 session=${finalRows.map { "${it.token}(parent=${it.parentSessionId})" }}, " +
                        "untouched session 仍在=${finalRows.any { it.token == "untouched" }}",
                )
            }
        }) { Text("⑥ UPDATE-via-Where (SET DSL) 验证") }

        Button(onClick = {
            scope.launch {
                val dbPath = Databases.freshDbPath(context, "flow.db")
                val db = AccountDbV4Builder(dbPath).setDriver(AndroidSQLiteDriver()).build()

                val flow = db.queryAccountV2Flow(AccountV2Columns.Name eq "flowtest")
                var emissionCount = 0
                val collectJob = launch {
                    flow.collect { rows ->
                        emissionCount++
                        push("⑦ flow 第 $emissionCount 次发出: ${rows.map { it.name }} (无需重新查询,InvalidationTracker 自动推送)")
                    }
                }

                delay(500) // let the first (immediate, no-prior-write) emission land
                db.accountDao().insert(AccountV2(name = "flowtest"))
                delay(1000) // let the post-write emission land
                db.accountDao().insert(AccountV2(name = "unrelated")) // same table, doesn't match WHERE
                delay(1000)

                collectJob.cancel()
                push("⑦ flow.db: 收集结束,共收到 $emissionCount 次推送 (期望3次: 初始空/插入flowtest后/插入unrelated后仍为空但仍推送)")
            }
        }) { Text("⑦ Flow 响应式查询验证") }

        Text("最近操作日志（新的在上面）:", style = MaterialTheme.typography.titleSmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(log) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
