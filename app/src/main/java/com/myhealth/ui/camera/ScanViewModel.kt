package com.myhealth.ui.camera

import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.di.OffLookup
import com.myhealth.di.ScanMessages
import com.myhealth.di.ScanSources
import com.myhealth.domain.engine.label.LabelParseResult
import com.myhealth.domain.engine.label.OcrLine
import com.myhealth.domain.engine.label.NutritionLabelParser
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Backs [ScanScreen] (PLAN P4.8).
 *
 * LABEL mode: one `ImageCapture` frame → ML Kit text → [NutritionLabelParser] → [DraftStore] →
 * the OCR review screen (P4.9). BARCODE mode: continuous `ImageAnalysis` frames until the first
 * EAN/UPC decodes, then the analyser is switched off and the code is looked up on Open Food Facts
 * (P4.10) before the ingredient editor opens, prefilled.
 *
 * Nothing here ever saves: both paths end in an editable form the user has to confirm (risk R9).
 */
class ScanViewModel(
    private val sources: ScanSources,
    private val off: OffLookup,
    private val store: DraftStore,
    private val cacheDir: File,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    fun setMode(mode: ScanMode) {
        if (mode == _state.value.mode) return
        _state.update {
            it.copy(mode = mode, lastError = null, manualBarcode = null, analysisActive = true)
        }
    }

    fun toggleTorch() {
        _state.update { it.copy(torchOn = !it.torchOn) }
    }

    fun dismissError() {
        _state.update { it.copy(lastError = null, manualBarcode = null, analysisActive = true) }
    }

    fun consumeNav() {
        _state.update { it.copy(nav = null) }
    }

    /** The shutter failed inside CameraX (no capture session, camera in use, …). */
    fun onCaptureFailed(cause: Throwable) {
        _state.update {
            it.copy(isProcessing = false, lastError = cause.message ?: "The camera could not take a picture.")
        }
    }

    /** LABEL mode: a single captured frame. Ownership of [frame] passes to this call. */
    fun onLabelCaptured(frame: ImageProxy) {
        if (_state.value.isProcessing) {
            frame.close()
            return
        }
        _state.update { it.copy(isProcessing = true, lastError = null, manualBarcode = null) }
        viewModelScope.launch {
            try {
                handleCapture(frame)
            } finally {
                frame.close()
            }
        }
    }

    /** BARCODE mode: one analyser frame. Ownership of [frame] passes to this call. */
    fun onAnalyzerFrame(frame: ImageProxy) {
        val current = _state.value
        if (current.isProcessing || !current.analysisActive || current.mode != ScanMode.BARCODE) {
            frame.close()
            return
        }
        _state.update { it.copy(isProcessing = true) }
        viewModelScope.launch {
            try {
                handleFrame(frame)
            } finally {
                frame.close()
            }
        }
    }

    private suspend fun handleCapture(frame: ImageProxy) {
        when (val captured = sources.captureLabel(frame, cacheDir)) {
            is Outcome.Err -> fail(ScanMessages.of(captured.error))
            is Outcome.Ok -> parseLabel(captured.value.lines, captured.value.imagePath)
        }
    }

    /** The shared tail of both LABEL paths: parse, park the draft, go to the review screen. */
    private fun parseLabel(lines: List<OcrLine>, imagePath: String?) {
        when (val parsed = NutritionLabelParser.parse(lines)) {
            is LabelParseResult.Failed -> fail(ScanMessages.NO_NUTRIENTS)
            is LabelParseResult.Success -> {
                store.put(
                    ScanDraft(
                        facts = parsed.draft,
                        warnings = parsed.warnings,
                        imagePath = imagePath,
                        source = ScanDraft.SOURCE_OCR,
                    ),
                )
                _state.update { it.reviewReady() }
            }
        }
    }

    private suspend fun handleFrame(frame: ImageProxy) {
        when (val code = sources.readBarcode(frame)) {
            is Outcome.Err -> fail(ScanMessages.of(code.error))
            is Outcome.Ok -> {
                val barcode = code.value
                if (barcode == null) {
                    _state.update { it.copy(isProcessing = false) }
                } else {
                    _state.update { it.copy(analysisActive = false) }
                    lookUp(barcode)
                }
            }
        }
    }

    /**
     * A found product opens the editor prefilled; a miss keeps the user on the scan screen with
     * the reason plus an "Enter it manually" way forward — never a dead end.
     */
    private suspend fun lookUp(barcode: String) {
        when (val result = off.lookup(barcode)) {
            is Outcome.Ok -> {
                store.put(result.value)
                _state.update {
                    it.copy(isProcessing = false, isFromPhoto = false, nav = ScanNav.Ingredient(barcode))
                }
            }
            is Outcome.Err -> _state.update {
                it.copy(
                    isProcessing = false,
                    isFromPhoto = false,
                    lastError = ScanMessages.of(result.error),
                    manualBarcode = barcode,
                )
            }
        }
    }

    /**
     * "From photo" (NOTE-6): a picked image runs the same pipeline as the camera — LABEL mode
     * recognises it and lands on the review screen, BARCODE mode reads its code and looks it up.
     * A `null` [uri] means the picker was dismissed.
     */
    fun onPhotoPicked(uri: Uri?) {
        if (uri == null) {
            _state.update { it.photoCancelled() }
            return
        }
        if (_state.value.isProcessing) return
        val mode = _state.value.mode
        _state.update { it.photoStarted() }
        viewModelScope.launch {
            when (mode) {
                ScanMode.LABEL -> handlePhotoLabel(uri)
                ScanMode.BARCODE -> handlePhotoBarcode(uri)
            }
        }
    }

    private suspend fun handlePhotoLabel(uri: Uri) {
        when (val captured = sources.recognizeLabel(uri, cacheDir)) {
            is Outcome.Err -> _state.update { it.photoFailed(ScanMessages.of(captured.error)) }
            is Outcome.Ok -> parseLabel(captured.value.lines, captured.value.imagePath)
        }
    }

    private suspend fun handlePhotoBarcode(uri: Uri) {
        when (val code = sources.scanBarcode(uri)) {
            is Outcome.Err -> _state.update { it.photoFailed(ScanMessages.of(code.error)) }
            is Outcome.Ok -> {
                val barcode = code.value
                if (barcode == null) {
                    _state.update { it.photoFailed(ScanMessages.NO_BARCODE_IN_PHOTO) }
                } else {
                    lookUp(barcode)
                }
            }
        }
    }

    /** The user accepted "Enter it manually" after a failed lookup. */
    fun enterManually() {
        val barcode = _state.value.manualBarcode ?: return
        store.put(ScanMessages.emptyDraft(barcode))
        _state.update {
            it.copy(lastError = null, manualBarcode = null, nav = ScanNav.Ingredient(barcode))
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(isProcessing = false, isFromPhoto = false, lastError = message) }
    }
}
