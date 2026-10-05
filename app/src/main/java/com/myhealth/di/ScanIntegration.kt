package com.myhealth.di

import android.content.Context
import android.net.Uri
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.myhealth.data.ocr.FrameImages
import com.myhealth.data.ocr.MlKitBarcodeSource
import com.myhealth.data.ocr.MlKitTextSource
import com.myhealth.domain.engine.label.OcrLine
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import java.io.File

/**
 * The scan screen's window onto `data/ocr` and `data/off` (PLAN P4.8/P4.10), following the
 * [HcIntegration] pattern: `ui/` must never import `com.myhealth.data.*` (enforced by
 * `ArchitectureTest`), and `di/` is the layer allowed to reference `data/` (§1.2).
 *
 * The `ImageProxy` that crosses this seam is an `androidx.camera` type, not a data-layer one, so
 * the camera screen can hand its frames straight over; everything ML-Kit-shaped (`InputImage`,
 * `Text`, `Barcode`) stays behind it.
 */

/** One label capture: the recognised lines plus the upright JPEG kept for the review screen. */
data class CapturedLabel(val lines: List<OcrLine>, val imagePath: String?)

interface ScanSources {
    /**
     * Recognises a single captured frame and stores it as a JPEG in [cacheDir] (P4.8 LABEL mode).
     * The caller keeps ownership of [frame] and must close it.
     */
    suspend fun captureLabel(frame: ImageProxy, cacheDir: File): Outcome<CapturedLabel>

    /**
     * The first EAN/UPC on an analyser frame, or `Ok(null)` when this frame carries none
     * (P4.8 BARCODE mode). The caller keeps ownership of [frame] and must close it.
     */
    suspend fun readBarcode(frame: ImageProxy): Outcome<String?>

    /**
     * "Scan from photo", LABEL mode (NOTE-6): the same recognition pipeline over a picked image
     * instead of a camera frame, and a copy of the picture in [cacheDir] for the review screen.
     */
    suspend fun recognizeLabel(uri: Uri, cacheDir: File): Outcome<CapturedLabel>

    /** "Scan from photo", BARCODE mode: the first product code on a picked image. */
    suspend fun scanBarcode(uri: Uri): Outcome<String?>
}

/** [ScanSources] backed by the unbundled ML Kit detectors held in [AppGraph]. */
class MlKitScanSources(
    private val context: Context,
    private val text: MlKitTextSource,
    private val barcode: MlKitBarcodeSource,
) : ScanSources {

    override suspend fun captureLabel(frame: ImageProxy, cacheDir: File): Outcome<CapturedLabel> {
        val bitmap = FrameImages.uprightBitmap(frame)
            ?: return Outcome.Err(AppError.Parse("camera-frame", "Unsupported capture format"))
        val imagePath = FrameImages.saveJpeg(bitmap, cacheDir)
        return when (val lines = text.recognize(InputImage.fromBitmap(bitmap, 0))) {
            is Outcome.Err -> lines
            is Outcome.Ok -> Outcome.Ok(CapturedLabel(lines.value, imagePath))
        }
    }

    override suspend fun readBarcode(frame: ImageProxy): Outcome<String?> {
        val image = FrameImages.inputImageFrom(frame)
            ?: FrameImages.uprightBitmap(frame)?.let { InputImage.fromBitmap(it, 0) }
            ?: return Outcome.Ok(null)
        return barcode.firstBarcode(image)
    }

    override suspend fun recognizeLabel(uri: Uri, cacheDir: File): Outcome<CapturedLabel> {
        val image = imageOf(uri) ?: return Outcome.Err(UNREADABLE_IMAGE)
        val imagePath = FrameImages.copyToCache(context, uri, cacheDir)
        return when (val lines = text.recognize(image)) {
            is Outcome.Err -> lines
            is Outcome.Ok -> Outcome.Ok(CapturedLabel(lines.value, imagePath))
        }
    }

    override suspend fun scanBarcode(uri: Uri): Outcome<String?> {
        val image = imageOf(uri) ?: return Outcome.Err(UNREADABLE_IMAGE)
        return barcode.firstBarcode(image)
    }

    /** `InputImage.fromFilePath` also applies the EXIF rotation, which a picked photo needs. */
    private fun imageOf(uri: Uri): InputImage? =
        runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()

    private companion object {
        val UNREADABLE_IMAGE = AppError.Parse("picked-image", "That image could not be opened.")
    }
}
