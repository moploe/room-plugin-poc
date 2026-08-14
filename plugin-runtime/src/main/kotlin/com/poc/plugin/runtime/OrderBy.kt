package com.poc.plugin.runtime

class OrderBy(val sql: String)

fun ColumnRef.asc(): OrderBy = OrderBy("`$name` ASC")
fun ColumnRef.desc(): OrderBy = OrderBy("`$name` DESC")

/** Combine into a multi-column ORDER BY: `colA.asc() then colB.desc()`. */
infix fun OrderBy.then(other: OrderBy): OrderBy = OrderBy("$sql, ${other.sql}")
