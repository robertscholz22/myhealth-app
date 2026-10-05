package com.myhealth.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.model.ImportKind
import com.myhealth.domain.model.ImportRecord
import com.myhealth.domain.repository.ActivityImporter
import com.myhealth.domain.repository.ImportKinds
import com.myhealth.domain.repository.ImportRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.sync.SyncScheduler
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** One picked document, remembered so "Import anyway" can re-run it with `force`. */
private data class PickedFile(val uri: String, val kind: ImportKind)

/**
 * Drives the Import screen (PLAN P7.6). The work itself runs in [com.myhealth.sync.ImportWorker];
 * this view model only starts it and folds its progress together with the `import_record` history.
 */
class ImportViewModel(
    private val importRepo: ImportRepository,
    private val importer: ActivityImporter,
    private val scheduler: SyncScheduler,
    private val pendingSharedUri: MutableStateFlow<String?>,
) : ViewModel() {

    private val unsupported = MutableStateFlow<String?>(null)
    private val picked = MutableStateFlow<PickedFile?>(null)
    private val undoing = MutableStateFlow(false)

    private val undoMessage = MutableStateFlow<UiMessage?>(null)

    /** One-shot result of the last [undo], resolved and shown by the screen's snackbar. */
    val message: StateFlow<UiMessage?> = undoMessage.asStateFlow()

    val state: StateFlow<ImportUiState> = combine(
        scheduler.observeImportState(),
        importRepo.observeRecent(HISTORY_LIMIT),
        unsupported,
        picked,
    ) { work, history, unsupportedName, pickedFile ->
        ImportUiState(
            work = work,
            history = history.map { it.toItem() },
            unsupportedFile = unsupportedName,
            canForce = pickedFile != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportUiState())

    /** A file the user picked, or one handed over by the share sheet. */
    fun start(uri: String, fileName: String, mimeType: String?, force: Boolean = false) {
        val kind = ImportKinds.forFileName(fileName) ?: ImportKinds.forMimeType(mimeType)
        if (kind == null) {
            unsupported.value = fileName
            return
        }
        unsupported.value = null
        picked.value = PickedFile(uri, kind)
        scheduler.startImport(uri, kind.name, force)
    }

    /** Re-runs the last pick with `force`, after the duplicate-file short-circuit. */
    fun importAnyway() {
        val file = picked.value ?: return
        scheduler.startImport(file.uri, file.kind.name, force = true)
    }

    /**
     * Undoes one import (§2.2.6): its source records go, activities another source also knows are
     * re-merged from what remains, and the file's checksum is forgotten so it can be imported
     * again. The counts come back as the snackbar message.
     */
    fun undo(importId: Long) {
        if (undoing.value) return
        undoing.value = true
        viewModelScope.launch {
            undoMessage.value = when (val outcome = importRepo.undo(importId)) {
                is Outcome.Ok -> UiMessage.of(
                    Res.string.import_undo_result_format,
                    outcome.value.activitiesDeleted,
                    outcome.value.activitiesKept,
                )
                is Outcome.Err -> UiMessage.of(Res.string.import_undo_failed)
            }
            undoing.value = false
        }
    }

    fun consumeMessage() {
        undoMessage.value = null
    }

    /** Clears the finished summary so the screen is ready for the next file. */
    fun dismissResult() {
        scheduler.clearImportState()
        unsupported.value = null
    }

    /** The share-sheet hand-off (P7.6); cleared with [clearSharedUri] once it has been started. */
    val sharedUri: StateFlow<String?> = pendingSharedUri.asStateFlow()

    fun clearSharedUri() {
        pendingSharedUri.value = null
    }

    private fun ImportRecord.toItem() = ImportHistoryItem(
        id = id,
        fileName = fileName,
        kind = kind,
        importedAtMillis = importedAtMillis,
        parsed = itemsParsed,
        inserted = itemsInserted,
        duplicate = itemsDuplicate,
        errorCount = errorCountOf(errorsJson),
    )

    companion object {
        const val HISTORY_LIMIT: Int = 20

        /** `errorsJson` is a JSON array of `{item, message}`; a malformed value counts as zero. */
        fun errorCountOf(errorsJson: String?): Int {
            if (errorsJson.isNullOrBlank()) return 0
            return runCatching { (Json.parseToJsonElement(errorsJson) as JsonArray).size }.getOrDefault(0)
        }
    }
}
