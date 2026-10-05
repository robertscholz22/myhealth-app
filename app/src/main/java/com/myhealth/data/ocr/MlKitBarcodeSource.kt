package com.myhealth.data.ocr

import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.myhealth.data.ocr.OcrErrors.await
import com.myhealth.domain.util.Outcome

/**
 * Continuous barcode reading for the scan screen (PLAN P4.8), restricted to the product formats
 * Open Food Facts is keyed by — EAN-13, EAN-8, UPC-A and UPC-E — which both speeds the detector
 * up and keeps QR codes from being mistaken for products.
 *
 * Uses the **unbundled** `play-services-mlkit-barcode-scanning` client (amendment A3); the same
 * [OcrErrors.MODEL_NOT_READY] window applies on the very first use.
 */
class MlKitBarcodeSource(
    // Built on first use — see [MlKitTextSource].
    provider: () -> BarcodeScanner = {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                )
                .build(),
        )
    },
) {

    private val scanner: BarcodeScanner by lazy(provider)

    /**
     * The first product code on [image], or `Outcome.Ok(null)` when the frame carries none —
     * "nothing on this frame" is the normal case for a continuous analyser, not an error.
     */
    suspend fun firstBarcode(image: InputImage): Outcome<String?> = try {
        val codes = scanner.process(image).await()
        Outcome.Ok(codes.firstNotNullOfOrNull { code -> code.rawValue?.trim()?.ifBlank { null } })
    } catch (e: Exception) {
        Outcome.Err(OcrErrors.toAppError(e))
    }

    fun close() {
        runCatching { scanner.close() }
    }
}
