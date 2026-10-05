package com.myhealth.platform

import com.myhealth.domain.engine.label.OcrLine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.Vision.VNBarcodeObservation
import platform.Vision.VNBarcodeSymbologyEAN13
import platform.Vision.VNBarcodeSymbologyEAN8
import platform.Vision.VNBarcodeSymbologyUPCE
import platform.Vision.VNDetectBarcodesRequest
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import kotlin.math.roundToInt

/**
 * Vision on iOS (P22.3), the counterpart of the ML Kit sources: text lines with pixel boxes for
 * the shared `NutritionLabelParser`, and the first EAN/UPC on a picture. Everything runs on an
 * upright copy of the image, so the boxes match what the review screen shows.
 */
@OptIn(ExperimentalForeignApi::class)
object IosVision {

    /** A picture redrawn upright at scale 1 (camera photos carry their rotation as metadata). */
    fun upright(image: UIImage): UIImage {
        val (width, height) = image.size.useContents { width to height }
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { scale = 1.0 }
        return UIGraphicsImageRenderer(CGSizeMake(width, height), format).imageWithActions {
            image.drawInRect(CGRectMake(0.0, 0.0, width, height))
        }
    }

    /** Saves [image] as a JPEG in [dir] for the OCR review screen; `null` when that fails. */
    fun saveJpeg(image: UIImage, dir: String): String? {
        val path = "$dir/label_${NSUUID().UUIDString}.jpg"
        val data = UIImageJPEGRepresentation(image, 0.85) ?: return null
        return if (data.writeToFile(path, atomically = true)) path else null
    }

    /** Recognised lines of an upright [image], top-left origin, in pixels. */
    suspend fun recognizeText(image: UIImage): List<OcrLine> = withContext(Dispatchers.IO) {
        val cgImage = image.CGImage ?: return@withContext emptyList()
        val width = CGImageGetWidth(cgImage).toDouble()
        val height = CGImageGetHeight(cgImage).toDouble()
        val request = VNRecognizeTextRequest(completionHandler = null).apply {
            recognitionLevel = VNRequestTextRecognitionLevelAccurate
            recognitionLanguages = listOf("de-DE", "en-US")
            usesLanguageCorrection = false
        }
        VNImageRequestHandler(cGImage = cgImage, options = emptyMap<Any?, Any>()).performRequests(listOf(request), error = null)
        request.results().orEmpty().filterIsInstance<VNRecognizedTextObservation>().mapNotNull { observation ->
            val text = (observation.topCandidates(1u).firstOrNull() as? VNRecognizedText)?.string ?: return@mapNotNull null
            // Vision's boxes are normalised with the origin bottom-left.
            observation.boundingBox.useContents {
                OcrLine(
                    text = text,
                    left = (origin.x * width).roundToInt(),
                    top = ((1.0 - origin.y - size.height) * height).roundToInt(),
                    right = ((origin.x + size.width) * width).roundToInt(),
                    bottom = ((1.0 - origin.y) * height).roundToInt(),
                )
            }
        }.sortedWith(compareBy({ it.top }, { it.left }))
    }

    /** The first product code (EAN-13, EAN-8, UPC-E) on [image], or `null`. */
    suspend fun detectBarcode(image: UIImage): String? = withContext(Dispatchers.IO) {
        val cgImage = image.CGImage ?: return@withContext null
        val request = VNDetectBarcodesRequest(completionHandler = null).apply {
            symbologies = listOf(VNBarcodeSymbologyEAN13, VNBarcodeSymbologyEAN8, VNBarcodeSymbologyUPCE)
        }
        VNImageRequestHandler(cGImage = cgImage, options = emptyMap<Any?, Any>()).performRequests(listOf(request), error = null)
        request.results().orEmpty().filterIsInstance<VNBarcodeObservation>().firstNotNullOfOrNull { it.payloadStringValue }
    }
}
