# RoomPluginPOC

一个基于 KSP 的 [Room 3.0.1](https://developer.android.com/kotlin/multiplatform/room)(`androidx.room3`)伴生插件。

**你不需要学习任何新注解。** 只要你的项目里已经有标准的 `@Entity` / `@Dao` / `@Database`,这个插件就会在编译期读取它们,自动生成:

1. **Schema 自动迁移** —— 不用再手写 `Migration(1, 2) { ... }`,新增表、新增列、新增索引全自动处理,而且任意旧版本可以一步升级到最新版本,不需要链式迁移。
2. **类型安全的 Where / Set DSL** —— 用 `AccountColumns.Name eq "bob"` 这种写法拼查询条件,而不是手写 SQL 字符串或者依赖 Room 的 `@Query` 反射校验。
3. **关系自动抓取(1:1 / 1:N / 多对多 / N 层嵌套)** —— 只要表之间有 `@ForeignKey`,插件就能自动生成"父带子"的批量查询函数,包括 `Flow` 响应式版本,完全不需要你手写 `@Relation` POJO。

> 目前这个仓库还没有发布到 Maven Central,只能作为 Gradle 子模块(`project(":plugin-runtime")` / `project(":plugin-processor")`)接入,见下文「快速接入」。

---

## 目录

- [环境要求](#环境要求)
- [快速接入](#快速接入)
- [5 分钟上手](#5-分钟上手)
- [功能详解](#功能详解)
  - [1. 自动 Schema 迁移](#1-自动-schema-迁移)
  - [2. Where DSL —— 类型安全查询条件](#2-where-dsl--类型安全查询条件)
  - [3. Set DSL + UPDATE-via-Where](#3-set-dsl--update-via-where)
  - [4. DELETE-via-Where](#4-delete-via-where)
  - [5. Flow 响应式查询](#5-flow-响应式查询)
  - [6. 关系自动生成](#6-关系自动生成)
  - [7. @Embedded 支持](#7-embedded-支持)
  - [8. 枚举字段](#8-枚举字段)
  - [9. JSON 字段](#9-json-字段)
  - [10. @Ignore](#10-ignore)
  - [11. 分块 IN() 查询,躲开 SQLite 999 参数上限](#11-分块-in-查询躲开-sqlite-999-参数上限)
  - [12. 事务组合](#12-事务组合)
  - [13. 可配置项](#13-可配置项)
- [生成产物命名速查表](#生成产物命名速查表)
- [已知限制](#已知限制)
- [项目结构](#项目结构)
- [如何跑测试自己验证](#如何跑测试自己验证)

---

## 环境要求

- Kotlin 2.1.0+
- KSP `2.1.0-1.0.29`+
- `androidx.room3:room3-runtime:3.0.1` / `androidx.room3:room3-compiler:3.0.1`(Room 3,不是 `androidx.room` 2.x)
- JVM 17
- 如果用到默认 JSON 转换器(见下文第 9 节),还需要 `kotlin("plugin.serialization")` 插件和 `kotlinx-serialization-json`

## 快速接入

在 `settings.gradle.kts` 里把本仓库的 `plugin-runtime`、`plugin-processor` 两个模块 include 进你的项目(或者直接把这两个目录拷到你自己的多模块项目里):

```kotlin
include(":plugin-runtime")
include(":plugin-processor")
```

然后在你要生成代码的模块(比如 `:app`)的 `build.gradle.kts` 里:

```kotlin
plugins {
    kotlin("jvm") // 或 kotlin("android")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization") // 只有用到第 9 节的默认 JSON 转换器时才需要
}

dependencies {
    implementation("androidx.room3:room3-runtime:3.0.1")
    ksp("androidx.room3:room3-compiler:3.0.1")

    implementation(project(":plugin-runtime"))
    ksp(project(":plugin-processor"))
}

ksp {
    arg("room.generateKotlin", "true") // Room 3 KSP 本身需要这个
}
```

`kotlin { jvmToolchain(17) }` 也要配上,插件本身是用 17 编译的。

## 5 分钟上手

正常写你的 Room 实体和 DAO,**不需要加任何新注解**:

```kotlin
@Entity(tableName = "account")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val active: Boolean = true,
)

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(a: Account): Long

    // 这个 stub 方法是触发 Where-DSL 查询生成的"开关",见下文第 2 节
    @RawQuery
    suspend fun query(query: RoomRawQuery): List<Account>
}

@Database(entities = [Account::class], version = 1, exportSchema = false)
abstract class AccountDb : RoomDatabase() {
    abstract fun dao(): AccountDao
}
```

跑一次编译(`./gradlew :app:kspKotlin` 或直接 Build),KSP 会在 `build/generated/ksp/.../` 下生成一堆文件,其中最常用的三个:

- `AccountGenerated.kt` —— `object AccountColumns { val Id = LongColumn("id"); val Name = StringColumn("name"); ... }`,还有 `AccountCreateTableSql()` / `AccountExpectedColumns()` 供自动迁移用。
- `AccountDaoWhereQueries.kt` —— 给你的 `AccountDao.query` 加了一个重载:`AccountDao.query(where: Where, orderBy: OrderBy? = null, limit: Int? = null, offset: Int? = null): List<Account>`。
- `AccountDbBuilder.kt` —— `fun AccountDbBuilder(path: String): RoomDatabase.Builder<AccountDb>`,已经帮你挂好了自动迁移,你只需要选driver:

```kotlin
val db = AccountDbBuilder(path)
    .setDriver(BundledSQLiteDriver()) // 或 Android 上的 AndroidSQLiteDriver()
    .build()

val id = db.dao().insert(Account(name = "root"))
val matches = db.dao().query(AccountColumns.Name eq "root")
```

以上就是最小闭环。下面是每个功能的详细说明。

---

## 功能详解

### 1. 自动 Schema 迁移

**你不用写 `Migration` 对象。** 每个 `@Database` 都会生成一个 `<Db>AutoMigrations(): Array<Migration>` 和一个 `<Db>Builder(path)` 函数,已经把迁移挂好了。

原理:每个迁移条目不管声明的起始版本是几,都会对**每一张表**执行同一段"创建或补齐"逻辑(`autoDiffMigration`,在 `plugin-runtime` 里):

- 表不存在 → 直接整张 `CREATE TABLE`(新增的 `@Entity` 场景)
- 表已存在 → `PRAGMA table_info()` 读现有列,跟期望的列做 diff,缺哪列就 `ALTER TABLE ADD COLUMN` 补哪列
- 索引一律 `CREATE INDEX IF NOT EXISTS`,天然幂等

这意味着**任意旧版本都能一步升级到最新版本**,不需要 1→2→3→4 这样链式迁移。已经用真实设备 + 版本跳跃(v1 直接跳到 v3)验证过。

限制(见「已知限制」):

- 只支持**新增**表、**新增**列、**新增**索引。列改名、删列、改类型不支持——这类迁移语义上需要人来判断(是删除数据还是搬迁数据),自动生成风险太大,故意没做。
- 单列外键(`REFERENCES` 子句)只有在"可空 + 无默认值"时才能安全地通过 `ALTER TABLE ADD COLUMN` 补出来(SQLite 自身限制)。不满足这个条件的外键列,如果是后续版本才新增到已存在的表里,约束不会生效——KSP 编译时会打印明确的 warning 告诉你具体是哪个字段。

### 2. Where DSL —— 类型安全查询条件

每个 `@Entity` 都会生成一个 `<Entity>Columns` 单例,每个可持久化字段对应一个类型化的列引用:

```kotlin
object AccountColumns {
    val Id = LongColumn("id")
    val Name = StringColumn("name")
    val Active = BooleanColumn("active")
}
```

在这些列引用上调用中缀函数拼出 `Where`。每个运算符的具体含义:

| 运算符 | 全称 / 含义 | 对应 SQL | 例子 |
|---|---|---|---|
| `eq` | **equals**,等于 | `col = ?` | `AccountColumns.Name eq "bob"` |
| `neq` | **not equals**,不等于 | `col != ?` | `AccountColumns.Name neq "bob"` |
| `gt` | **greater than**,大于 | `col > ?` | `AccountColumns.Id gt 100L` |
| `gte` | **greater than or equal**,大于等于 | `col >= ?` | `AccountColumns.Id gte 100L` |
| `lt` | **less than**,小于 | `col < ?` | `AccountColumns.Id lt 100L` |
| `lte` | **less than or equal**,小于等于 | `col <= ?` | `AccountColumns.Id lte 100L` |
| `between` | 介于两者之间(闭区间,含两端) | `col BETWEEN ? AND ?` | `AccountColumns.Id.between(100L..200L)` |
| `like` | 模糊匹配(SQL 的 `LIKE`,`%` 匹配任意长度、`_` 匹配单字符) | `col LIKE ?` | `AccountColumns.Name like "root%"` |
| `in` | 属于某个集合(**in**,包含在……之中;`in` 是 Kotlin 关键字,要用反引号调用) | `col IN (?, ?, ...)` | `` AccountColumns.Id `in` listOf(1L, 2L, 3L) `` |
| `isNull` | 为空(是个属性,不是函数,取值直接得到 `Where`) | `col IS NULL` | `AccountColumns.ParentId.isNull` |
| `isNotNull` | 不为空 | `col IS NOT NULL` | `AccountColumns.ParentId.isNotNull` |

各列类型支持哪些运算符:

| 列类型 | 支持的运算符 |
|---|---|
| `StringColumn` | `eq` `neq` `like` `in` |
| `LongColumn` / `IntColumn` | `eq` `neq` `gt` `gte` `lt` `lte` `between` `in` |
| `ShortColumn` | `eq` `gt` `lt` |
| `ByteColumn` | `eq` |
| `DoubleColumn` / `FloatColumn` | `eq` `neq` `gt` `gte` `lt` `lte` `between` |
| `BooleanColumn` | `eq` |
| `ByteArrayColumn` | `eq` |
| 任意列 | `isNull` `isNotNull` |
| 枚举字段(生成为 `StringColumn`) | `eq` `neq` `in` 额外支持直接传枚举常量,不用手写 `.name` |

组合条件用 `and` / `or` / `not`:

```kotlin
val where = (AccountColumns.Active eq true) and (AccountColumns.Name like "root%")
val recent = AccountColumns.Id.between(100L..200L) or AccountColumns.Name.isNull
```

**要让某个 DAO 的查询方法支持 `Where` 重载,你需要在该 DAO 里放一个这样的 stub 方法**(方法名随意,但必须是 `@RawQuery`、单个 `RoomRawQuery` 参数、返回 `List<Entity>`):

```kotlin
@RawQuery
suspend fun query(query: RoomRawQuery): List<Account>
```

插件会在旁边生成一个**同名重载**:

```kotlin
suspend fun AccountDao.query(where: Where, orderBy: OrderBy? = null, limit: Int? = null, offset: Int? = null): List<Account>
```

用法:

```kotlin
val page = db.dao().query(
    where = AccountColumns.Active eq true,
    orderBy = AccountColumns.Name.asc(),
    limit = 20,
    offset = 40,
)
```

排序:`col.asc()` / `col.desc()`,多列排序用 `then` 连接:`AccountColumns.Active.desc() then AccountColumns.Name.asc()`。

> 为什么不是直接改造你的 `@RawQuery` 方法本身?因为 KSP 不能修改已有函数体,只能新增重载——这也是为什么这个 stub 方法必须存在:它是插件识别"这个实体需要 Where 重载"的钩子。

### 3. Set DSL + UPDATE-via-Where

每个 `@Database` 会生成一个 `update<Entity>Where(where: Where, set: SetClause): Int`(返回受影响行数),**不经过 `@Dao`**,直接走 `RoomDatabase.useWriterConnection`。

> 为什么不用 `@RawQuery` 做 UPDATE?经过真机实测确认:Room 3 的 `@RawQuery` 走的是只读连接池,写操作要么直接抛"attempt to write a readonly database",要么在内存库上悄悄地 0 行生效——这是 Room 3 本身的限制,不是本插件生成代码的 bug。所以 UPDATE/DELETE 都改成直接用 `useWriterConnection`。

`SetClause` 构造方式(每种列类型对应的 infix 函数):

```kotlin
// 普通赋值
AccountColumns.Name set "new-name"
AccountColumns.ParentId.setNull()          // 清空可空列

// 算术运算 —— 在 SQLite 里就地计算,不是先读出来再算再写回去,
// 天然对并发写安全(多个并发 +1 不会互相覆盖丢更新)
CounterColumns.Value increment 1L
CounterColumns.Value decrement 1L
PriceColumns.Amount multiply 1.1
PriceColumns.Amount divide 2.0

// 列到列的拷贝,0 个绑定参数,纯 SQL 层面 `colA = colB`
SessionColumns.ParentSessionId setToColumn SessionColumns.Id

// 枚举列直接传常量,不用 .name
MemberColumns.Role set Role.ADMIN

// 用 + 组合多个赋值
val set = (AccountColumns.Name set "bob") + (AccountColumns.Active set true)
```

用法:

```kotlin
val updated = db.updateAccountWhere(AccountColumns.Id eq targetId, set)
```

### 4. DELETE-via-Where

同理,生成 `delete<Entity>Where(where: Where): Int`:

```kotlin
val deleted = db.deleteAccountWhere(AccountColumns.Active eq false)
```

### 5. Flow 响应式查询

对每个"可关系化"的实体(字段都是普通类型、`@Embedded`,或者用默认 JSON 转换器——没有自定义 `@ColumnTypeConverters` 覆盖,见第 9 节),会额外生成:

```kotlin
fun AccountDb.queryAccountFlow(where: Where, orderBy: OrderBy? = null, limit: Int? = null, offset: Int? = null): Flow<List<Account>>
```

内部基于 `RoomDatabase.invalidationTracker.createFlow(table)`。语义(已用专门的实验用例验证):**订阅时立即发射一次当前数据,之后每次该表发生写入都会重新查询并再发射一次**。

```kotlin
db.queryAccountFlow(AccountColumns.Active eq true).collect { activeAccounts ->
    // 首次订阅立即拿到当前数据,之后 account 表任何写入都会重新推送
}
```

### 6. 关系自动生成

**这是本插件的核心能力。** 只要两张表之间存在 `@ForeignKey`,插件会自动分析出关系类型,完全不需要你手写 `@Relation` 或嵌套 POJO。

#### 判定规则

- **1:1 还是 1:N**:看外键的子表列本身是不是唯一的(是主键,或者被一个 `unique` 索引覆盖)。是唯一的 → 1:1;不是 → 1:N。
- **多对多**:自动识别"junction 形状"的实体——复合主键恰好两列,每一列各自是一个单列外键,分别指向两个不同的、可管理的实体。满足这个形状就会被当成 M:N 的中间表,不需要任何额外标注。
- **自引用**(比如 `Session.parentSessionId -> Session.id`)天然支持,父子都解析成同一个类,得到一棵"直接子节点"树。
- 只有**单列主键**的实体能做关系的"起点"(需要按 id 查询);复合主键实体会被安全地排除在关系生成之外,不会生成错误代码。

#### 生成什么

以 1:N 为例(`Account` 1--N `Session`):

```kotlin
data class AccountWithSessionList(val account: Account, val sessionList: List<Session>)

suspend fun AccountDb.getAccountWithSessionList(id: Long): AccountWithSessionList?
suspend fun AccountDb.getAllAccountWithSessionList(ids: List<Long>): List<AccountWithSessionList>
fun AccountDb.getAccountWithSessionListFlow(id: Long): Flow<AccountWithSessionList?>
fun AccountDb.getAllAccountWithSessionListFlow(ids: List<Long>): Flow<List<AccountWithSessionList>>
suspend fun AccountDb.queryAccountWithSessionList(where: Where, orderBy: OrderBy? = null, limit: Int? = null, offset: Int? = null): List<AccountWithSessionList>
fun AccountDb.queryAccountWithSessionListFlow(where: Where, orderBy: OrderBy? = null, limit: Int? = null, offset: Int? = null): Flow<List<AccountWithSessionList>>
```

`getAll*` 系列内部固定是 2 次查询(父表一次、子表一次,按 id 批量 `IN (...)`),不管传多少个 id 都不会变成 N+1。`query*` 系列是"先按 Where 查出匹配的根 id,再复用 `getAll*`",所以也不是 N+1。所有 `*Flow` 变体会监听关系链上涉及到的**每一张表**,不只是根表——子表写入也会触发重新推送(已用专门测试验证:只写子表、不动父表,Flow 依然重新发射)。

多对多(`Author` M--N `Book` via `AuthorBook`):

```kotlin
data class AuthorWithBookViaAuthorBook(
    val author: Author,
    val bookWithJunctionList: List<Pair<Book, AuthorBook>>, // 连 junction 表自己的额外列也保留,不会丢
)

suspend fun AuthorDb.getAuthorWithBookViaAuthorBook(id: Long): AuthorWithBookViaAuthorBook?
suspend fun AuthorDb.getAllAuthorWithBookViaAuthorBook(ids: List<Long>): List<AuthorWithBookViaAuthorBook>
// + Flow / query / queryFlow 变体同上
```

#### N 层嵌套关系链

关系可以像 Room 自己处理嵌套 `@Relation` POJO 一样一直往下钻——但**你不需要手写任何嵌套 POJO**,插件纯粹从外键图里递归推导。比如 `Author` --M:N--> `Book` --1:N--> `Review`:

```kotlin
data class BookWithReviewList(val book: Book, val reviewList: List<Review>)
data class AuthorWithBookThenReviewList(val author: Author, val bookNestedList: List<BookWithReviewList>)

suspend fun LibraryDb.getAuthorWithBookThenReviewList(id: Long): AuthorWithBookThenReviewList?
suspend fun LibraryDb.getAllAuthorWithBookThenReviewList(ids: List<Long>): List<AuthorWithBookThenReviewList>
fun LibraryDb.getAuthorWithBookThenReviewListFlow(id: Long): Flow<AuthorWithBookThenReviewList?>
fun LibraryDb.queryAuthorWithBookThenReviewListFlow(where: Where, ...): Flow<List<AuthorWithBookThenReviewList>>
```

链上的每一跳都可以是 1:1 / 1:N / M:N 里的任意一种(1:1 作为中间跳也支持,不是只能当链的终点),命名规则是 `Root + With + Hop1Then + Hop2Then + ... + 最后一跳的关系标签`。

链能钻多深由 `maxNestDepth` 控制,默认 **3 层**,可配置(见第 13 节)——因为自引用实体理论上能无限递归,链的数量还会随深度乘性增长,必须有一个明确的上限,不能不设限地生成。

批量抓取始终是"每一层一次查询",不会因为深度增加就变成指数级查询数。

### 7. @Embedded 支持

`@Embedded` 字段会被拍平成普通列,同时在生成的 `read<Entity>()` 里正确地按原样嵌套构造回来,包括可空 `@Embedded` 整体读回 `null`(当它所有拍平列都是 `NULL` 时)。`@Embedded` 实体不会被排除在关系生成之外——可以正常作为关系链的父或子。

```kotlin
data class Address(val city: String, val zip: String)

data class Member(
    @PrimaryKey val id: Long = 0,
    @Embedded(prefix = "addr_") val address: Address,
)
```

`MemberColumns` 里会直接出现 `City`/`Zip` 两个独立列引用(取自 `Address` 内部属性名,大写开头),对应的底层 SQL 列名是加了 prefix 的 `addr_city`/`addr_zip`,但 `*Columns` 对象里的引用名本身不带 prefix。

### 8. 枚举字段

枚举属性会被当成 `TEXT` 列(存 `.name`),复用 Room 自带的枚举转换。`Where`/`Set` DSL 对枚举列额外提供了直接传枚举常量的重载(见第 2、3 节),不需要每次手写 `.name`。

### 9. JSON 字段

任何标了 `@Serializable`(kotlinx.serialization)的字段类型,如果没有显式指定 `@ColumnTypeConverters`,插件会**自动生成一个默认的 JSON 转换器**(基于 `kotlinx.serialization.json.Json`),存成 `TEXT` 列,读关系时也能正确 `jsonDecode` 回来。

如果你要**覆盖**某个字段的默认转换器,用自己的 `@ColumnTypeConverters`,有一个必须注意的坑(已经过 Room 3.0.1 实测确认,不是猜测):

```kotlin
// 正确 —— 用 @field: 显式指定 use-site target
@field:ColumnTypeConverters(MyConverter::class)
val shipping: Shipping

// 错误 —— 不加 @field: 的话注解会被 Kotlin 绑定到构造函数参数而不是字段,
// Room 的按列覆盖查找机制看不到它,你的覆盖会被默认转换器悄悄地忽略掉
@ColumnTypeConverters(MyConverter::class)
val shipping: Shipping
```

有显式 `@ColumnTypeConverters` 覆盖的字段所在的实体,不会被算作"可关系化"(因为插件没法知道你的自定义转换逻辑,没法安全地在关系抓取里复用),这类实体拿不到 `get<X>With<Y>List` 之类的关系函数、也拿不到 `query<X>Flow`。只用默认 JSON 转换器(没有覆盖)的实体则完全正常参与关系生成。

#### 混淆(R8/ProGuard)包里还能正确还原成实体类吗?

**能。** 已经用真机 + 真实开启 `isMinifyEnabled = true` 的 release 包(`proguard-rules.pro` 里刻意没写任何 kotlinx.serialization 相关的手动 keep 规则,只依赖它自己内置的 consumer rules)做过端到端实测:写入 `Shipping(address = "221B Baker Street", zip = "NW16XE")`,混淆包里读出来的 JSON 原文是 `{"address":"221B Baker Street","zip":"NW16XE"}`,再通过 `dao.getAllDefault()` 读回来解码,字段值完全正确——加密/解密过程中字段名没有被混淆掉。

原因:

- kotlinx.serialization 是**编译期编译器插件**,`Json.encodeToString`/`decodeFromString` 在 Kotlin 编译阶段(R8 跑之前)就已经被直接接到了为你的 `@Serializable` 类生成的具体 `serializer()` 实现上,不是运行时按类名反射查找——R8 统一改名不会破坏这种"调用点和被调用方法一起改名"的绑定关系。
- 实际写进 JSON 里的字段名(`address`/`zip`)是编译期就固化成字符串字面量的,来自你 Kotlin 属性的原始名字——R8 的标识符混淆只改类名/方法名/字段名这些符号,不会去改字符串常量的内容,所以 JSON 文本里的 key 名字永远是你写的原始属性名。
- `kotlinx-serialization-json` 这个依赖自带 consumer proguard rules(打包在它的 jar 里,Gradle/R8 会自动应用),已经帮你 keep 住了生成的 serializer 类,一般不需要你自己再写额外的混淆规则。

但有两个**跟混淆无关、但同样会影响"JSON 还能不能还原成实体类"**的真实风险点,值得一起注意:

- **多态序列化**(sealed class / interface,通过 `SerializersModule` 注册子类型)会在运行时按类名查具体子类型——这种情况下类名混淆是真的会出问题的。本插件目前的默认转换器只处理具体已知类型,不涉及多态,所以这条不适用于插件生成的代码,但如果你自己在 `@Serializable` 字段里用了多态,要自己额外小心。
- **跨版本字段增删**:默认的 `Json` 实例没有开 `ignoreUnknownKeys`,如果你后续给 `@Serializable` 类加了字段或删了字段,旧版本 App 写入的旧 JSON 用新版本的类解码,遇到"JSON 里有 key 但新类里没有对应属性"会直接抛 `SerializationException`,不是静默兼容——这是数据 schema 演进的问题,不是混淆的问题,但升级版本时容易和混淆问题搞混,一起提一下。

### 10. @Ignore

标了 `@Ignore` 的属性会被完全排除在生成的 schema(`CREATE TABLE`/`ExpectedColumns`)和 `read<Entity>()` 构造调用之外,和 Room 自己的语义一致——该属性需要在构造函数里有默认值(不然生成的读取代码编译不过,这也是 Room 本身对 `@Ignore` 构造参数的要求)。

### 11. 分块 IN() 查询,躲开 SQLite 999 参数上限

SQLite 单条语句的绑定参数有一个经典上限(通常 999)。插件生成的所有批量关系查询(`getAll*`)内部已经自动按 chunk 分批查询再合并结果,不管你传多少个 id 进去都不会触发"too many SQL variables"(已经用 1200+ 条真实数据实测验证过分块边界)。

如果你**自己**用 `SomeColumn.in(bigIdList)` 拼 `Where`,单条语句依然会撞上限——这种情况下用 `plugin-runtime` 提供的两个通用 helper:

```kotlin
// 读:自动分块查询再合并成一个 List
val rows = chunkedInQuery(bigIdList) { chunk -> db.dao().query(AccountColumns.Id `in` chunk) }

// 写(delete/update-where 同理):自动分块执行再把受影响行数加总
val deleted = chunkedInWrite(bigIdList) { chunk -> db.deleteAccountWhere(AccountColumns.Id `in` chunk) }
```

### 12. 事务组合

每个生成的函数(`update<Entity>Where`、`delete<Entity>Where`、`get<X>With<Y>` 等)内部各自独立调用 `useReaderConnection`/`useWriterConnection`。**如果只是单独调用一个生成函数,它本身天然是原子的**(单条 SQL 语句自带原子性)。

如果你要把**多个**生成函数调用合并成一个原子事务(比如"删旧记录 + 插入新记录"要么全成功要么全失败),不需要插件提供任何新 API——直接用 Room 自带的 `db.withWriteTransaction { ... }` 包起来就行,里面嵌套调用几个生成函数都会自动共享同一个事务:

```kotlin
db.withWriteTransaction {
    db.deleteAccountWhere(AccountColumns.Id eq oldId)
    db.updateSessionWhere(SessionColumns.AccountId eq oldId, SessionColumns.AccountId set newId)
    // 这里如果抛异常,上面两步会一起回滚
}
```

> 注意:普通的 `db.useWriterConnection { ... }` **不会**开启真正的 SQL 事务(它只是拿连接,不发 `BEGIN`),嵌套在里面的多个生成调用各自独立提交,不是原子的——必须用 `withWriteTransaction`(或 Room 提供的其它 `withTransaction` 系列)才会真的开事务。这一点已经用两组对照测试验证过(`TransactionCompositionExperimentTest`)。

### 13. 可配置项

通过 KSP processor option 配置(不需要 fork 这个插件):

```kotlin
ksp {
    arg("roomPluginMaxNestDepth", "4")        // 嵌套关系链最大深度,默认 3
    arg("roomPluginSqliteInChunkSize", "800") // 分块 IN() 查询每批大小,默认 900
}
```

---

## 生成产物命名速查表

以实体 `Parent`(单列主键)、`Child`(1:N 或 1:1 于 Parent)为例,`X` = `Parent`,`Y` = `Child`:

| 生成物 | 触发条件 | 命名 |
|---|---|---|
| `<Entity>Columns` | 任意 `@Entity` | `object <Entity>Columns` |
| `<Entity>CreateTableSql()` / `ExpectedColumns()` / `Indices()` | 任意 `@Entity` | 见名 |
| `read<Entity>(stmt)` | 实体"可关系化"(见第 5 节说明) | 见名 |
| `<Dao>.query(where, ...)` | DAO 里有 `@RawQuery fun x(RoomRawQuery): List<Entity>` stub | 与 stub 同名的重载 |
| `<Db>Builder(path)` / `<Db>AutoMigrations()` | 任意 `@Database` | 见名 |
| `query<Entity>Flow(where, ...)` | 实体可关系化 | 见名 |
| `delete<Entity>Where(where)` / `update<Entity>Where(where, set)` | 任意 `@Database` 里的实体 | 见名 |
| 1:1 关系 | `Y` 的外键列本身唯一 | `get/getAll/*Flow/query/queryFlow` + `XWithY` |
| 1:N 关系 | `Y` 的外键列不唯一 | 同上但 `XWithYList` |
| M:N 关系 | junction 形状实体 `J` | 同上但 `XWithYViaJ` |
| N 层嵌套 | 链上每一跳 | `Root + With + Hop1Then + Hop2Then + ... + 末跳标签` |

---

## 已知限制

以下场景**明确不支持**,不是遗漏,是有意为之或成本收益不划算:

- **`@DatabaseView` / FTS 全文搜索** —— 没做。
- **迁移中的列改名 / 删列 / 改类型** —— 自动迁移只做"新增",这类变更语义上必须人工介入。
- **独立的 processor 单元测试框架**(kotlin-compile-testing)—— 目前的验证方式是在 `:poc` 模块里用真实 Room 数据库(`BundledSQLiteDriver`)跑集成测试,不是隔离的编译期单测。
- **发布到 Maven Central / CI / 完整文档站 / iOS-JS-WASM 多平台验证** —— 都还没做,目前只能当 Gradle 子模块接入,只验证过 JVM + Android。
- **自引用多对多**(同一实体在 junction 表里通过两个外键指向自己,比如"好友关系")—— 处理器会干净地跳过,不生成关系,也不会报错或生成坏代码。
- **同一对实体之间有多条外键**(比如 `Book` 同时有 `authorId` 和 `editorId` 都指向 `Author`)—— 只有第一条外键会生成关系,第二条会在 KSP 编译日志里打印明确的 warning 说明被跳过了,不是静默失败。
- **复合主键实体** —— 不能作为关系的起点(1:1/1:N 的父、M:N 的左右两边、嵌套链的根),会被安全地排除在关系代码生成之外,但仍然正常支持 `Where`/`Set` DSL 的增删改查。
- **`setNull()` 用在 NOT NULL 列上** —— 编译期不拦截,运行时会正常抛 `NOT NULL constraint failed`,和其它违反约束的写法后果一致。

## 项目结构

```
plugin-runtime/    运行时支持库(Where/Set DSL、自动迁移 diff 逻辑、JSON/枚举辅助函数)——你的 app 要 implementation 这个模块
plugin-processor/  KSP processor 本体——你的 app 要 ksp() 这个模块,自己不会被打进最终产物
poc/               插件的"用例 + 集成测试"仓库,每个子包演示一类场景(newtable 迁移、wheredsl 查询、nestedmn 嵌套关系、jsonconv JSON、ignoretest @Ignore、ambiguousfk 多外键……),想看某个功能怎么用直接翻对应包最快
app/               真机 Demo App,7 个按钮分别演示迁移、Where 查询、JSON、新建表+FK+索引、关系抓取、UPDATE-via-Where、Flow
```

## 如何跑测试自己验证

```bash
./gradlew :plugin-runtime:compileKotlin :plugin-processor:compileKotlin
./gradlew :poc:test           # 全量集成测试,真实 SQLite,不是 mock
./gradlew :app:assembleDebug  # 真机 Demo APK(有 old/new 两个 flavor)
```

`poc/src/test/kotlin` 下每个测试文件对应一个功能点,文件名基本就是功能名,想确认某个 API 的具体行为,直接去对应测试文件看断言最准确。
