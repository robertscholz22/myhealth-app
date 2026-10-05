package com.myhealth.ui.imports

import com.myhealth.domain.model.ImportKind
import com.myhealth.sync.ImportWorkState

/** One row of the `import_record` history list (§4.2 "Import"). */
data class ImportHistoryItem(
    val id: Long,
    val fileName: String,
    val kind: ImportKind,
    val importedAtMillis: Long,
    val parsed: Int,
    val inserted: Int,
    val duplicate: Int,
    val errorCount: Int,
)

/**
 * State of the Import screen (PLAN P7.6). [work] is the single import work slot as WorkManager
 * reports it, so the progress survives rotation and leaving the screen.
 */
data class ImportUiState(
    val work: ImportWorkState = ImportWorkState(),
    val history: List<ImportHistoryItem> = emptyList(),
    /** Set when a picked or shared document's type is not one the importer understands. */
    val unsupportedFile: String? = null,
    /** True once a file has been picked, so the duplicate case can offer "Import anyway". */
    val canForce: Boolean = false,
) {
    val isRunning: Boolean get() = work.isRunning
}
