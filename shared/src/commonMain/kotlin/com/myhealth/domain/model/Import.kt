package com.myhealth.domain.model

import com.myhealth.domain.util.AppError

/** Running totals of one import (PLAN P7.5); the same four counts `import_record` stores. */
data class ImportCounts(
    val parsed: Int = 0,
    val inserted: Int = 0,
    val merged: Int = 0,
    val duplicate: Int = 0,
    val failed: Int = 0,
) {
    val handled: Int get() = inserted + merged + duplicate
}

/** One item inside the file that could not be read; collected into `import_record.errorsJson`. */
data class ImportItemError(val item: String, val message: String)

/**
 * What an import reports while it runs (P7.5). The flow always ends with exactly one of
 * [AlreadyImported], [Finished] or [Failed].
 */
sealed interface ImportProgress {
    data class Started(val fileName: String, val kind: ImportKind) : ImportProgress

    data class Working(
        val counts: ImportCounts,
        val currentItem: String? = null,
        val errors: List<ImportItemError> = emptyList(),
    ) : ImportProgress

    /** The file's SHA-256 is already in `import_record` and `force` was not set. */
    data class AlreadyImported(val previous: ImportRecord) : ImportProgress

    data class Finished(
        val record: ImportRecord,
        val counts: ImportCounts,
        val errors: List<ImportItemError> = emptyList(),
    ) : ImportProgress

    data class Failed(val error: AppError) : ImportProgress
}
