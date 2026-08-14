package com.poc.plugin.runtime

/**
 * Runs [fetch] once per [chunkSize]-sized slice of [ids] and concatenates the results, so a
 * user-authored `SomeColumn.in(ids)` [Where] clause never exceeds SQLite's ~999 bound-parameter
 * limit for a single statement. This mirrors what the KSP-generated relation batch-fetch
 * functions already do internally for their own `IN (...)` queries - this is the same pattern
 * exposed for `Where`s the caller builds by hand (e.g. `query(SomeColumns.Id `in` bigIdList)`),
 * which the processor has no way to see or chunk for you.
 */
suspend fun <ID, T> chunkedInQuery(
    ids: Collection<ID>,
    chunkSize: Int = 900,
    fetch: suspend (List<ID>) -> List<T>,
): List<T> {
    if (ids.isEmpty()) return emptyList()
    val result = ArrayList<T>(ids.size)
    for (chunk in ids.chunked(chunkSize)) {
        result += fetch(chunk)
    }
    return result
}

/**
 * The write-side counterpart to [chunkedInQuery]: runs [write] once per [chunkSize]-sized slice
 * of [ids] and sums the affected-row counts, so a user-authored `SomeColumn.in(ids)` [Where]
 * passed to `delete<Entity>Where`/`update<Entity>Where` never exceeds SQLite's ~999
 * bound-parameter limit either - those generated functions run the [Where] as a single
 * statement with no chunking of their own, since they have no way to know the caller's `in()`
 * list is unbounded.
 */
suspend fun <ID> chunkedInWrite(
    ids: Collection<ID>,
    chunkSize: Int = 900,
    write: suspend (List<ID>) -> Int,
): Int {
    if (ids.isEmpty()) return 0
    var affected = 0
    for (chunk in ids.chunked(chunkSize)) {
        affected += write(chunk)
    }
    return affected
}
