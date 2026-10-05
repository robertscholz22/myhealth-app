package com.myhealth.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.di.OffLookup
import com.myhealth.di.ScanMessages
import com.myhealth.domain.engine.label.LabelParseResult
import com.myhealth.domain.engine.label.NutritionLabelParser
import com.myhealth.domain.engine.label.OcrLine
import com.myhealth.platform.IosVision
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.UIKit.UIImage

/**
 * Backs the iOS scan screen (P22.3), mirroring the Android `ScanViewModel` on the shared
 * [ScanUiState]: LABEL mode turns a photo (camera or picked) into lines via Vision →
 * [NutritionLabelParser] → [DraftStore] → review; BARCODE mode takes the first code AVFoundation
 * (camera) or Vision (picked photo) reads and looks it up on Open Food Facts. Nothing is saved
 * here — both paths end in a form the user confirms.
 */
class IosScanViewModel(
    private val off: OffLookup,
    private val store: DraftStore,
    private val imageDir: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    fun setMode(mode: ScanMode) {
        if (mode == _state.value.mode) return
        _state.update { it.copy(mode = mode, lastError = null, manualBarcode = null, analysisActive = true) }
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

    /** The shutter was pressed; [capture] takes the photo. */
    fun onCapture(capture: suspend () -> UIImage?) {
        if (_state.value.isProcessing) return
        _state.update { it.copy(isProcessing = true, lastError = null, manualBarcode = null) }
        viewModelScope.launch {
            val image = capture()
            if (image == null) fail(CAPTURE_FAILED) else recognizeLabel(image)
        }
    }

    /** The camera read a code (BARCODE mode only, while the analyser is active). */
    fun onBarcode(code: String) {
        val current = _state.value
        if (current.isProcessing || !current.analysisActive || current.mode != ScanMode.BARCODE) return
        _state.update { it.copy(isProcessing = true, analysisActive = false) }
        viewModelScope.launch { lookUp(code) }
    }

    fun onPhotoStarted() {
        _state.update { it.photoStarted() }
    }

    /** "From photo" returned; `null` means the picker was dismissed. */
    fun onPhotoPicked(image: UIImage?) {
        if (image == null) {
            _state.update { it.photoCancelled() }
            return
        }
        val mode = _state.value.mode
        viewModelScope.launch {
            when (mode) {
                ScanMode.LABEL -> recognizeLabel(image, fromPhoto = true)
                ScanMode.BARCODE -> {
                    val code = IosVision.detectBarcode(IosVision.upright(image))
                    if (code == null) _state.update { it.photoFailed(ScanMessages.NO_BARCODE_IN_PHOTO) } else lookUp(code)
                }
            }
        }
    }

    private suspend fun recognizeLabel(image: UIImage, fromPhoto: Boolean = false) {
        val upright = IosVision.upright(image)
        val lines = IosVision.recognizeText(upright)
        platform.Foundation.NSLog("IosScan: %ld text lines", lines.size.toLong())
        parseLabel(lines, IosVision.saveJpeg(upright, imageDir), fromPhoto)
    }

    private fun parseLabel(lines: List<OcrLine>, imagePath: String?, fromPhoto: Boolean) {
        when (val parsed = NutritionLabelParser.parse(lines)) {
            is LabelParseResult.Failed ->
                if (fromPhoto) _state.update { it.photoFailed(ScanMessages.NO_NUTRIENTS) } else fail(ScanMessages.NO_NUTRIENTS)
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

    /** A found product opens the editor prefilled; a miss offers "Enter it manually". */
    private suspend fun lookUp(barcode: String) {
        when (val result = off.lookup(barcode)) {
            is Outcome.Ok -> {
                store.put(result.value)
                _state.update { it.copy(isProcessing = false, isFromPhoto = false, nav = ScanNav.Ingredient(barcode)) }
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

    fun enterManually() {
        val barcode = _state.value.manualBarcode ?: return
        store.put(ScanMessages.emptyDraft(barcode))
        _state.update { it.copy(lastError = null, manualBarcode = null, nav = ScanNav.Ingredient(barcode)) }
    }

    private fun fail(message: String) {
        _state.update { it.copy(isProcessing = false, isFromPhoto = false, lastError = message) }
    }

    private companion object {
        const val CAPTURE_FAILED = "The camera could not take a picture."
    }
}
