package com.poc.plugin.runtime

import androidx.sqlite.SQLiteStatement

/**
 * One `column = <expr>` assignment. [sqlFragment] is the full `` `col` = ... `` clause - not
 * just the column name - so an assignment can reference the column's own current value (e.g.
 * `` `col` = `col` + ? `` for [LongColumn.increment]) instead of always being a plain literal
 * replace. [placeholderCount] is how many `?` placeholders [sqlFragment] actually contains -
 * usually 1, but 0 for a pure column-to-column copy like `` `col` = `otherCol` `` (see
 * [LongColumn.setToColumn]), which needs no bound value at all.
 */
class Assignment(val sqlFragment: String, val placeholderCount: Int = 1, val binder: Binder)

/**
 * Typed SET-clause DSL for UPDATE ... WHERE, mirroring [Where]'s design. Combine multiple
 * assignments with `+`, e.g. `(NameColumn set "bob") + (AgeColumn set 30)`.
 */
class SetClause(val assignments: List<Assignment>) {
    val sql: String get() = assignments.joinToString(", ") { it.sqlFragment }
    val placeholderCount: Int get() = assignments.sumOf { it.placeholderCount }
}

operator fun SetClause.plus(other: SetClause): SetClause = SetClause(assignments + other.assignments)

fun SetClause.bindingFunction(startingAt: Int = 1): (SQLiteStatement) -> Unit = { stmt ->
    var pos = startingAt
    for (a in assignments) {
        if (a.placeholderCount > 0) a.binder(stmt, pos)
        pos += a.placeholderCount
    }
}

fun ColumnRef.setNull(): SetClause = SetClause(listOf(Assignment("`$name` = ?") { s, i -> s.bindNull(i) }))

infix fun StringColumn.set(value: String): SetClause = SetClause(listOf(Assignment("`$name` = ?") { s, i -> s.bindText(i, value) }))
// mirrors WhereDsl's enum-eq/neq/in overloads - lets a caller SET an enum column directly
// without spelling `.name` themselves, since it's generated as a plain StringColumn.
infix fun <E : Enum<E>> StringColumn.set(value: E): SetClause = set(value.name)
infix fun LongColumn.set(value: Long): SetClause = SetClause(listOf(Assignment("`$name` = ?") { s, i -> s.bindLong(i, value) }))
infix fun IntColumn.set(value: Int): SetClause = SetClause(listOf(Assignment("`$name` = ?") { s, i -> s.bindLong(i, value.toLong()) }))
infix fun ShortColumn.set(value: Short): SetClause = SetClause(listOf(Assignment("`$name` = ?") { s, i -> s.bindLong(i, value.toLong()) }))
infix fun ByteColumn.set(value: Byte): SetClause = SetClause(listOf(Assignment("`$name` = ?") { s, i -> s.bindLong(i, value.toLong()) }))
infix fun DoubleColumn.set(value: Double): SetClause = SetClause(listOf(Assignment("`$name` = ?") { s, i -> s.bindDouble(i, value) }))
infix fun FloatColumn.set(value: Float): SetClause = SetClause(listOf(Assignment("`$name` = ?") { s, i -> s.bindFloat(i, value) }))
infix fun BooleanColumn.set(value: Boolean): SetClause = SetClause(listOf(Assignment("`$name` = ?") { s, i -> s.bindBoolean(i, value) }))
infix fun ByteArrayColumn.set(value: ByteArray): SetClause = SetClause(listOf(Assignment("`$name` = ?") { s, i -> s.bindBlob(i, value) }))

// `col = col + ?` / `col = col - ?` - the whole point is doing the arithmetic *in SQLite*
// against whatever the column's current value is at UPDATE time, not a value read earlier by
// the caller (which would be a stale read racing any concurrent writer).
infix fun LongColumn.increment(delta: Long): SetClause = SetClause(listOf(Assignment("`$name` = `$name` + ?") { s, i -> s.bindLong(i, delta) }))
infix fun IntColumn.increment(delta: Int): SetClause = SetClause(listOf(Assignment("`$name` = `$name` + ?") { s, i -> s.bindLong(i, delta.toLong()) }))
infix fun DoubleColumn.increment(delta: Double): SetClause = SetClause(listOf(Assignment("`$name` = `$name` + ?") { s, i -> s.bindDouble(i, delta) }))
infix fun FloatColumn.increment(delta: Float): SetClause = SetClause(listOf(Assignment("`$name` = `$name` + ?") { s, i -> s.bindFloat(i, delta) }))
infix fun LongColumn.decrement(delta: Long): SetClause = increment(-delta)
infix fun IntColumn.decrement(delta: Int): SetClause = increment(-delta)
infix fun DoubleColumn.decrement(delta: Double): SetClause = increment(-delta)
infix fun FloatColumn.decrement(delta: Float): SetClause = increment(-delta)

// `col = col * ?` / `col = col / ?` - same "compute in SQLite, not client-side" reasoning as
// increment/decrement above.
infix fun LongColumn.multiply(factor: Long): SetClause = SetClause(listOf(Assignment("`$name` = `$name` * ?") { s, i -> s.bindLong(i, factor) }))
infix fun IntColumn.multiply(factor: Int): SetClause = SetClause(listOf(Assignment("`$name` = `$name` * ?") { s, i -> s.bindLong(i, factor.toLong()) }))
infix fun DoubleColumn.multiply(factor: Double): SetClause = SetClause(listOf(Assignment("`$name` = `$name` * ?") { s, i -> s.bindDouble(i, factor) }))
infix fun FloatColumn.multiply(factor: Float): SetClause = SetClause(listOf(Assignment("`$name` = `$name` * ?") { s, i -> s.bindFloat(i, factor) }))
infix fun LongColumn.divide(divisor: Long): SetClause = SetClause(listOf(Assignment("`$name` = `$name` / ?") { s, i -> s.bindLong(i, divisor) }))
infix fun IntColumn.divide(divisor: Int): SetClause = SetClause(listOf(Assignment("`$name` = `$name` / ?") { s, i -> s.bindLong(i, divisor.toLong()) }))
infix fun DoubleColumn.divide(divisor: Double): SetClause = SetClause(listOf(Assignment("`$name` = `$name` / ?") { s, i -> s.bindDouble(i, divisor) }))
infix fun FloatColumn.divide(divisor: Float): SetClause = SetClause(listOf(Assignment("`$name` = `$name` / ?") { s, i -> s.bindFloat(i, divisor) }))

// `col = otherCol` - a pure column-to-column copy, no bound value at all (placeholderCount =
// 0). Each variant is restricted to columns of matching Kotlin type so this stays type-safe;
// there's deliberately no cross-type overload (e.g. copying a Long column into a String one)
// since SQLite would silently coerce it in ways that don't match the Kotlin type system.
infix fun StringColumn.setToColumn(other: StringColumn): SetClause = SetClause(listOf(Assignment("`$name` = `${other.name}`", placeholderCount = 0) { _, _ -> }))
infix fun LongColumn.setToColumn(other: LongColumn): SetClause = SetClause(listOf(Assignment("`$name` = `${other.name}`", placeholderCount = 0) { _, _ -> }))
infix fun IntColumn.setToColumn(other: IntColumn): SetClause = SetClause(listOf(Assignment("`$name` = `${other.name}`", placeholderCount = 0) { _, _ -> }))
infix fun DoubleColumn.setToColumn(other: DoubleColumn): SetClause = SetClause(listOf(Assignment("`$name` = `${other.name}`", placeholderCount = 0) { _, _ -> }))
infix fun FloatColumn.setToColumn(other: FloatColumn): SetClause = SetClause(listOf(Assignment("`$name` = `${other.name}`", placeholderCount = 0) { _, _ -> }))
infix fun BooleanColumn.setToColumn(other: BooleanColumn): SetClause = SetClause(listOf(Assignment("`$name` = `${other.name}`", placeholderCount = 0) { _, _ -> }))
