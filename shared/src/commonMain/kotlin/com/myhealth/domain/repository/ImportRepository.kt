package com.myhealth.domain.repository

import com.myhealth.domain.model.ImportKind
import com.myhealth.domain.model.ImportProgress
import com.myhealth.domain.model.ImportRecord
import com.myhealth.domain.model.ImportUndoSummary
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `import_record` (PLAN §2.2.6, P7.5). The SHA-256 file hash is the duplicate-import guard:
 * the pipeline short-circuits when [getByHash] already returns a record (unless forced).
 */
interface ImportRepository {

    fun observeRecent(limit: Int): Flow<List<ImportRecord>>

    suspend fun getById(id: Long): ImportRecord?

    suspend fun getByHash(fileHashSha256: String): ImportRecord?

    suspend fun record(record: ImportRecord): Outcome<Long>

    suspend fun delete(id: Long): Outcome<Unit>

    /**
     * Undoes one import: every `activity_source_record` it wrote is dropped and the canonical
     * activity re-merged from the sources that remain (or deleted when none do), then the
     * `import_record` itself goes, so the file's checksum is forgotten and it can be imported
     * again. Finally a load recompute is requested from the earliest day the undo touched.
     */
    suspend fun undo(importId: Long): Outcome<ImportUndoSummary>

    /**
     * BUG-19: shrinks the error list of imports written by ≤ 0.9.1, which stored one error per
     * wellness FIT file of a Garmin export — megabytes in one row, more than Android's cursor
     * window holds, so the Import screen and the backup export crashed reading it. Run on app
     * start; returns the number of rows repaired.
     */
    suspend fun repairOversizedErrors(): Outcome<Int>

    /**
     * Removes source records of a file-import kind (`CSV_IMPORT`/`FIT_IMPORT`) that were never
     * stamped with an `import_record` (BUG-12b hotfix 1.0.3): activities imported before DB v4,
     * which "Undo import" cannot find because it selects by `importRecordId`. Each is removed
     * through the same re-merge/delete path [undo] uses — an activity Health Connect also knows
     * is kept — and a load recompute is requested from the earliest day touched.
     */
    suspend fun removeOrphanedImportData(): Outcome<ImportUndoSummary>
}

/**
 * The import pipeline as the UI sees it (P7.5/P7.6). The `uri` is a `String` because `domain/`
 * may not reference `android.net.Uri` (rule R6); the implementation parses it back.
 *
 * Implemented by `data/repository/ImportService`, which hashes the file, short-circuits on a
 * known hash unless [force], decodes it, ingests in chunks through the same seam Health Connect
 * uses, writes the `import_record` and requests a load recompute.
 */
interface ActivityImporter {
    fun import(uri: String, kind: ImportKind, force: Boolean = false): Flow<ImportProgress>
}

/** Which [ImportKind] a picked document is, from its file name (P7.6); `null` when unsupported. */
object ImportKinds {

    fun forFileName(fileName: String): ImportKind? = when {
        fileName.endsWith(".fit", ignoreCase = true) -> ImportKind.FIT_FILE
        fileName.endsWith(".csv", ignoreCase = true) -> ImportKind.GARMIN_CSV
        fileName.endsWith(".zip", ignoreCase = true) -> ImportKind.GARMIN_ZIP
        else -> null
    }

    /** Fallback for a document whose name carries no usable extension. */
    fun forMimeType(mimeType: String?): ImportKind? = when (mimeType?.lowercase()) {
        "application/zip", "application/x-zip-compressed" -> ImportKind.GARMIN_ZIP
        "text/csv", "text/comma-separated-values", "application/csv" -> ImportKind.GARMIN_CSV
        else -> null
    }
}
