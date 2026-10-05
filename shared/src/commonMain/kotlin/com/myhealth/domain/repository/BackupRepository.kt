package com.myhealth.domain.repository

import com.myhealth.domain.util.Outcome

/** How an imported backup meets the data already on the device (PLAN P8.4). */
enum class BackupMode {
    /** Wipe every table and insert the backup's rows verbatim, inside one transaction. */
    REPLACE,

    /**
     * Insert only what is missing, matched on each table's natural key — `(source, externalId)`
     * for source records and body/sleep rows, `barcode` or `name + brand` for ingredients, `day`
     * for the daily caches, ids nowhere. Nothing already on the device is overwritten or removed,
     * so merging an old backup can never undo newer data.
     */
    MERGE,
}

/** What an export wrote, or what an import found and inserted. */
data class BackupSummary(
    val schemaVersion: Int,
    val exportedAtMillis: Long,
    val appVersion: String,
    /** Rows per table, table name as in SQLite; only non-empty tables are listed. */
    val rowsPerTable: Map<String, Int>,
    /** Rows actually written by an import (always equal to [totalRows] for an export). */
    val rowsWritten: Int,
) {
    val totalRows: Int get() = rowsPerTable.values.sum()

    val tableCount: Int get() = rowsPerTable.size
}

/**
 * JSON backup of the whole database (PLAN P8.4). Never includes the DataStore settings or any
 * credential — those are re-entered on a new device by design.
 */
interface BackupRepository {

    /** Streams the whole database into the document at [uri] as one JSON object. */
    suspend fun export(uri: String): Outcome<BackupSummary>

    /** Reads the document at [uri] and applies it with [mode]. */
    suspend fun import(uri: String, mode: BackupMode): Outcome<BackupSummary>
}
