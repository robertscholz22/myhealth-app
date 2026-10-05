package com.myhealth.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.repository.BackupMode
import com.myhealth.domain.repository.BackupRepository
import com.myhealth.domain.repository.BackupSummary
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the last export or import did — the "counts" the screen shows (P8.4). */
data class BackupResult(
    val isImport: Boolean,
    val mode: BackupMode?,
    val summary: BackupSummary,
)

/** ViewModel state for [BackupScreen]. */
data class BackupUiState(
    val isRunning: Boolean = false,
    /** The mode the next import runs in; the export path ignores it. */
    val importMode: BackupMode = BackupMode.MERGE,
    val result: BackupResult? = null,
    val error: UiMessage? = null,
) {
    val canStart: Boolean get() = !isRunning
}

/**
 * Backs [BackupScreen] (PLAN P8.4). The screen owns the document picker — a `ViewModel` cannot
 * launch an `ActivityResultContract` — and hands the picked URI straight over as a string.
 */
class BackupViewModel(private val backupRepo: BackupRepository) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun setImportMode(mode: BackupMode) = _state.update { it.copy(importMode = mode) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** The user picked (or created) a destination document: write the whole database into it. */
    fun export(uri: String?) {
        if (uri == null || _state.value.isRunning) return
        run(isImport = false, mode = null) { backupRepo.export(uri) }
    }

    /** The user picked a backup file: apply it in the currently selected [BackupUiState.importMode]. */
    fun import(uri: String?) {
        if (uri == null || _state.value.isRunning) return
        val mode = _state.value.importMode
        run(isImport = true, mode = mode) { backupRepo.import(uri, mode) }
    }

    private fun run(
        isImport: Boolean,
        mode: BackupMode?,
        block: suspend () -> Outcome<BackupSummary>,
    ) {
        _state.update { it.copy(isRunning = true, error = null, result = null) }
        viewModelScope.launch {
            when (val outcome = block()) {
                is Outcome.Ok -> _state.update {
                    it.copy(isRunning = false, result = BackupResult(isImport, mode, outcome.value))
                }
                is Outcome.Err -> _state.update {
                    it.copy(isRunning = false, error = backupErrorMessage(outcome.error))
                }
            }
        }
    }
}

/** User-facing text for a backup failure; [AppError.Validation] already carries a written one. */
fun backupErrorMessage(error: AppError): UiMessage = when (error) {
    is AppError.Validation -> UiMessage.of(Res.string.backup_error_dynamic_format, error.message)
    is AppError.Parse -> UiMessage.of(Res.string.backup_error_parse_format, error.detail)
    is AppError.Storage -> UiMessage.of(Res.string.backup_error_storage)
    else -> UiMessage.of(Res.string.backup_error_generic)
}
