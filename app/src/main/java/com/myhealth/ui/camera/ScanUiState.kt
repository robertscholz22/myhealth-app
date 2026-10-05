package com.myhealth.ui.camera

/** The two things the camera screen can do (PLAN §4.2 "Scan (camera)"). */
enum class ScanMode { LABEL, BARCODE }

/** Where the scan screen wants to go next; consumed once by the screen (§1.7 one-shot events). */
sealed interface ScanNav {
    /** A label was parsed into `DraftStore` — review it before it becomes an ingredient. */
    data object Review : ScanNav

    /** A barcode was read (and looked up, or not) — continue in the ingredient editor. */
    data class Ingredient(val barcode: String) : ScanNav
}

/**
 * State of [ScanScreen] (PLAN §4.2: `mode`, `permissionState`, `isProcessing`, `lastError`).
 * The camera permission itself is owned by the composable — a `ViewModel` cannot launch an
 * `ActivityResultContract` — so it is not part of this state.
 */
data class ScanUiState(
    val mode: ScanMode = ScanMode.LABEL,
    val torchOn: Boolean = false,
    /** A capture or a lookup is in flight: the shutter is disabled and a spinner shows. */
    val isProcessing: Boolean = false,
    /** `false` once a barcode was accepted, so the analyser stops feeding frames (P4.8). */
    val analysisActive: Boolean = true,
    val lastError: String? = null,
    /** Set together with [lastError] when the barcode is known but the product was not found. */
    val manualBarcode: String? = null,
    val nav: ScanNav? = null,
    /** True while a picked image (not a camera frame) is being recognised — see [photoStarted]. */
    val isFromPhoto: Boolean = false,
) {
    val isBarcodeMode: Boolean get() = mode == ScanMode.BARCODE

    /**
     * "From photo" was started (NOTE-6): the shutter and the picker are disabled, any previous
     * error is cleared and — in BARCODE mode — the live analyser is muted so a frame cannot race
     * the picked image to the result.
     */
    fun photoStarted(): ScanUiState = copy(
        isProcessing = true,
        isFromPhoto = true,
        analysisActive = false,
        lastError = null,
        manualBarcode = null,
    )

    /** The picker returned nothing (the user backed out): straight back to where we were. */
    fun photoCancelled(): ScanUiState = copy(
        isProcessing = false,
        isFromPhoto = false,
        analysisActive = mode == ScanMode.BARCODE,
    )

    /** A picked image carried no barcode at all — not an ML Kit failure, just the wrong picture. */
    fun photoFailed(message: String): ScanUiState =
        copy(isProcessing = false, isFromPhoto = false, lastError = message)

    /** A label (from either source) was parsed into the draft store; the review screen is next. */
    fun reviewReady(): ScanUiState =
        copy(isProcessing = false, isFromPhoto = false, nav = ScanNav.Review)
}
