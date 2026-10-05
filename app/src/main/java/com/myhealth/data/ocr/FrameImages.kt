package com.myhealth.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.net.Uri
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.FileOutputStream

/**
 * CameraX frame → ML Kit input (and → a JPEG on disk) for P4.8.
 *
 * `ImageProxy.getImage()` is `@ExperimentalGetImage`, and it is the fast path: an `ImageAnalysis`
 * frame is YUV_420_888, which `InputImage.fromMediaImage` consumes without a copy. A single-shot
 * `ImageCapture` frame is JPEG instead — a format `fromMediaImage` does **not** accept — so that
 * path decodes the buffer to a bitmap ([uprightBitmap]) which doubles as the picture the OCR
 * review screen shows.
 */
object FrameImages {

    private const val JPEG_QUALITY = 85

    /** File name of the last captured label inside the app's cache directory. */
    const val LAST_CAPTURE = "scan_last.jpg"

    /**
     * Zero-copy [InputImage] for an analyser frame; `null` when the frame is not a media image
     * ML Kit supports (then use [uprightBitmap] and `InputImage.fromBitmap`).
     */
    // CameraX declares @ExperimentalGetImage as a Java marker, so the opt-in has to be the
    // androidx one (Kotlin's @OptIn does not apply to it, and lint's UnsafeOptInUsageError knows).
    @AndroidXOptIn(markerClass = [ExperimentalGetImage::class])
    fun inputImageFrom(frame: ImageProxy): InputImage? {
        val media = frame.image ?: return null
        if (media.format == ImageFormat.JPEG) return null
        return InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees)
    }

    /**
     * The frame as an upright bitmap: `ImageProxy.toBitmap()` handles JPEG/YUV/RGBA but never
     * applies the sensor rotation, so it is applied here — the result needs no rotation hint at
     * all, which is what both `InputImage.fromBitmap(bitmap, 0)` and the review screen want.
     */
    fun uprightBitmap(frame: ImageProxy): Bitmap? {
        val bitmap = runCatching { frame.toBitmap() }.getOrNull() ?: return null
        val degrees = frame.imageInfo.rotationDegrees
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /**
     * Copies a picked image ([uri], from the photo picker) into [dir] so the OCR review screen can
     * show it after the content permission has lapsed. The bytes are copied verbatim — ML Kit is
     * handed the original `Uri` — so the file may well be a PNG under a `.jpg` name; every reader
     * here is `BitmapFactory`, which sniffs the header and does not care.
     */
    fun copyToCache(
        context: Context,
        uri: Uri,
        dir: File,
        name: String = LAST_CAPTURE,
    ): String? = runCatching {
        val file = File(dir, name)
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "the picked image could not be opened" }
            FileOutputStream(file).use { out -> input.copyTo(out) }
        }
        file.absolutePath
    }.getOrNull()

    /** Writes [bitmap] as a JPEG into [dir], returning its absolute path (`null` on failure). */
    fun saveJpeg(bitmap: Bitmap, dir: File, name: String = LAST_CAPTURE): String? = runCatching {
        val file = File(dir, name)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
        file.absolutePath
    }.getOrNull()
}
