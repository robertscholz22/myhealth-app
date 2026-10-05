package com.myhealth.ui.camera

import com.myhealth.domain.model.NutritionFactsDraft
import com.myhealth.domain.util.EngineWarning
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A scanned product on its way to the ingredient editor (PLAN P4.8/P4.10): the parsed or
 * downloaded facts, the warnings that came with them, and — for a label scan — the captured
 * picture the review screen shows.
 *
 * This is never persisted: OCR results must be confirmed by a human first (brief §2.1, risk R9).
 */
data class ScanDraft(
    val facts: NutritionFactsDraft,
    val warnings: List<EngineWarning> = emptyList(),
    /** Absolute path of the captured JPEG under `cacheDir`, or `null` for a barcode lookup. */
    val imagePath: String? = null,
    val name: String = "",
    val brand: String = "",
    val barcode: String = "",
    /** Goes into `Ingredient.source`: `"OCR"` for a label scan, `"OFF"` for a barcode lookup. */
    val source: String = SOURCE_OCR,
) {
    companion object {
        const val SOURCE_OCR = "OCR"
        const val SOURCE_OFF = "OFF"
    }
}

/**
 * The one-slot hand-off between Scan → OCR review → Ingredient edit (P4.8): a [NutritionFactsDraft]
 * plus a bitmap path is far too big for a navigation argument, so the screens exchange it through
 * this process-wide singleton (held by `AppGraph`) and the consumer [take]s it, leaving the slot
 * empty so a later, unrelated visit to the editor never sees a stale scan.
 */
@OptIn(ExperimentalAtomicApi::class)
class DraftStore {

    private val slot = AtomicReference<ScanDraft?>(null)

    fun put(draft: ScanDraft) {
        slot.store(draft)
    }

    /** Reads without consuming — the review screen may be recreated on rotation. */
    fun peek(): ScanDraft? = slot.load()

    /** Reads and clears — used by the ingredient editor, which prefills exactly once. */
    fun take(): ScanDraft? = slot.exchange(null)

    fun clear() {
        slot.store(null)
    }
}
