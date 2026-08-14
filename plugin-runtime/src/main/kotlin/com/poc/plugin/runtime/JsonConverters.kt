package com.poc.plugin.runtime

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Small helpers so a user's own @ColumnTypeConverter functions don't each need to repeat
 * Json.encodeToString/decodeFromString boilerplate. There is no single reusable generic
 * converter object here on purpose: Room 3's @ColumnTypeConverter must be a concrete,
 * non-generic function per (fromType, toType) pair, so every entity field still needs its
 * own pair of tiny functions - these just make each one a one-liner.
 *
 * IMPORTANT: when you annotate a primary-constructor property with @ColumnTypeConverters
 * to override a database-level default converter for that field only, you MUST use the
 * explicit `@field:` use-site target:
 *
 *   @field:ColumnTypeConverters(MyConverter::class)
 *   val shipping: Shipping
 *
 * Without `@field:`, Kotlin binds the annotation to the constructor parameter instead of
 * the backing field, Room's per-column override lookup doesn't see it, and your override
 * is silently ignored in favor of the database-level default. Confirmed empirically against
 * Room 3.0.1 - this is not a hypothetical footgun, it's the default outcome.
 */
inline fun <reified T> jsonEncode(value: T): String = Json.encodeToString(value)

inline fun <reified T> jsonDecode(value: String): T = Json.decodeFromString(value)
