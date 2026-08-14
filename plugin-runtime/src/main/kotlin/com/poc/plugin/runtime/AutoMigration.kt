package com.poc.plugin.runtime

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * name: the column's name in the SQLite table.
 * alterClause: the full column definition Room would use, ready to append to
 * "ALTER TABLE x ADD COLUMN ". Never includes PRIMARY KEY/UNIQUE/REFERENCES - SQLite
 * forbids adding those via ALTER, so :plugin-processor never emits them here.
 */
data class ExpectedColumn(val name: String, val alterClause: String)

/** name: index name, for existence checks. createSql: a full "CREATE [UNIQUE] INDEX IF NOT EXISTS ..." statement. */
data class IndexDef(val name: String, val createSql: String)

/**
 * The one piece of runtime logic that makes a Migration's SQL data-dependent instead of
 * a fixed compile-time string:
 *  - if the table doesn't exist yet (a brand-new @Entity added this version), run the full
 *    CREATE TABLE - this used to crash (ALTER TABLE against a nonexistent table)
 *  - if it does exist, diff PRAGMA table_info() against the expected columns and issue
 *    ALTER TABLE ADD COLUMN for whatever's missing
 *  - either way, ensure every expected index exists (CREATE INDEX IF NOT EXISTS is
 *    idempotent, safe to always run)
 */
suspend fun autoDiffMigration(
    connection: SQLiteConnection,
    table: String,
    createTableSql: String,
    expected: List<ExpectedColumn>,
    indices: List<IndexDef> = emptyList(),
) {
    if (!tableExists(connection, table)) {
        connection.execSQL(createTableSql)
    } else {
        val existing = mutableSetOf<String>()
        val stmt = connection.prepare("PRAGMA table_info(`$table`)")
        try {
            while (stmt.step()) existing += stmt.getText(1)
        } finally {
            stmt.close()
        }
        for (col in expected) {
            if (col.name !in existing) {
                connection.execSQL("ALTER TABLE `$table` ADD COLUMN ${col.alterClause}")
            }
        }
    }

    for (index in indices) {
        connection.execSQL(index.createSql)
    }
}

private suspend fun tableExists(connection: SQLiteConnection, table: String): Boolean {
    val stmt = connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?")
    try {
        stmt.bindText(1, table)
        return stmt.step()
    } finally {
        stmt.close()
    }
}
