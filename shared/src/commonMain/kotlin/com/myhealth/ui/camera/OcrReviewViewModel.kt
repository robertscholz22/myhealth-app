package com.myhealth.ui.camera

import androidx.lifecycle.ViewModel
import com.myhealth.domain.engine.label.LabelValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Backs [OcrReviewScreen] (PLAN P4.9). Reads the scan the camera screen left in the [DraftStore]
 * — with [DraftStore.peek], so a rotation does not lose it — lets every field be edited, and on
 * [accept] writes the confirmed values back into the store for the ingredient editor to prefill.
 *
 * **Never saves.** There is no repository here at all, which is the point: an OCR result only
 * becomes an `Ingredient` through the editor's explicit Save (brief §2.1, risk R9).
 */
class OcrReviewViewModel(private val store: DraftStore) : ViewModel() {

    private val _state = MutableStateFlow(reviewStateOf(store.peek()))
    val state: StateFlow<OcrReviewUiState> = _state.asStateFlow()

    fun setValue(field: OcrField, value: Double?) {
        _state.update { current ->
            val next = current.withValue(field, value)
            next.copy(warnings = LabelValidator.validate(next.acceptedFacts()))
        }
    }

    /** "Use per-serving column": swaps the numbers and the basis note (P4.9). */
    fun setUsePerServing(use: Boolean) {
        _state.update { current ->
            val next = current.withPerServing(use)
            next.copy(warnings = LabelValidator.validate(next.acceptedFacts()))
        }
    }

    fun setName(name: String) {
        _state.update { it.copy(name = name) }
    }

    fun setBrand(brand: String) {
        _state.update { it.copy(brand = brand) }
    }

    /** Hands the confirmed facts to the ingredient editor through the store. */
    fun accept() {
        val current = _state.value
        store.put(
            ScanDraft(
                facts = current.acceptedFacts(),
                warnings = current.warnings,
                imagePath = current.imagePath,
                name = current.name,
                brand = current.brand,
                barcode = current.barcode,
                source = current.source,
            ),
        )
    }

    /** Cancel and Retake both drop the scan — nothing half-confirmed survives the screen. */
    fun discard() {
        store.clear()
    }
}
