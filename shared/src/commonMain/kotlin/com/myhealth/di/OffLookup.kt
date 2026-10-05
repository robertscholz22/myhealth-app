package com.myhealth.di

import com.myhealth.data.ocr.OcrErrorCodes
import com.myhealth.data.off.OffClient
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFactsDraft
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.ui.camera.ScanDraft

/** Barcode → prefilled draft, so the scan and the manual "Look up barcode" paths share one call. */
interface OffLookup {
    suspend fun lookup(barcode: String): Outcome<ScanDraft>
}

/** [OffLookup] backed by the real [OffClient]. */
class OffProductLookup(private val client: OffClient) : OffLookup {

    override suspend fun lookup(barcode: String): Outcome<ScanDraft> =
        when (val result = client.fetch(barcode)) {
            is Outcome.Err -> result
            is Outcome.Ok -> Outcome.Ok(
                ScanDraft(
                    facts = result.value.facts,
                    imagePath = null,
                    name = result.value.name.orEmpty(),
                    brand = result.value.brand.orEmpty(),
                    barcode = result.value.barcode,
                    source = ScanDraft.SOURCE_OFF,
                ),
            )
        }
}

/**
 * User-facing text for a scan/lookup failure. It lives in `di/` because the codes it switches on
 * are defined in `data/` ([OcrErrors], [OffClient]) and `ui/` may not import them.
 */
object ScanMessages {

    const val NO_BARCODE_IN_PHOTO =
        "No barcode was found in that picture. Pick a sharper photo of the code."

    const val NO_NUTRIENTS =
        "No nutrition table was recognised. Fill the frame with the table and try again."

    fun of(error: AppError): String = when {
        error is AppError.Network && error.code == OcrErrorCodes.MODEL_NOT_READY ->
            "Text recognition model is still downloading, try again in a moment."
        error is AppError.Network && error.code == OcrErrorCodes.RECOGNITION_FAILED ->
            "The camera could not read that. Try again with more light and less glare."
        error is AppError.Network && error.code == OffClient.NOT_FOUND ->
            "Product not found in Open Food Facts."
        error is AppError.Network && error.code == OffClient.THROTTLED ->
            "Too many Open Food Facts lookups in the last minute. Wait a moment and try again."
        error is AppError.Network -> "No connection to Open Food Facts. Check your network."
        error is AppError.Parse -> "Open Food Facts sent a response this app could not read."
        error is AppError.Validation -> error.message
        else -> "Something went wrong. Please try again."
    }

    /**
     * The "found nothing, let the user type it" fallback: only the barcode is known, and whatever
     * the user fills in next is their own data, so the provenance is `MANUAL`, not `OFF`.
     */
    fun emptyDraft(barcode: String): ScanDraft = ScanDraft(
        facts = NutritionFactsDraft(basis = MeasureBasis.PER_100G),
        barcode = barcode,
        source = MANUAL_SOURCE,
    )

    private const val MANUAL_SOURCE = "MANUAL"
}
