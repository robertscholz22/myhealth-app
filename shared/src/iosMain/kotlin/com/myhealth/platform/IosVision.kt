package com.myhealth.platform

import com.myhealth.data.ocr.OcrLineMapper
import com.myhealth.domain.engine.label.OcrLine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreGraphics.CGImageRef
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.Vision.VNRequest
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
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.Vision.VNBarcodeObservation
import platform.Vision.VNBarcodeSymbologyEAN13
import platform.Vision.VNBarcodeSymbologyEAN8
import platform.Vision.VNBarcodeSymbologyUPCE
import platform.Vision.VNDetectBarcodesRequest
import platform.Vision.VNDetectBarcodesRequestRevision1
import platform.Vision.VNDetectBarcodesRequestRevision2
import platform.Vision.VNDetectBarcodesRequestRevision3
import platform.Vision.VNDetectBarcodesRequestRevision4
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

    /** Recognised lines of an upright [image] in reading order, top-left origin, in pixels. */
    suspend fun recognizeText(image: UIImage): List<OcrLine> = withContext(Dispatchers.IO) {
        val cgImage = image.CGImage ?: return@withContext emptyList()
        val width = CGImageGetWidth(cgImage).toDouble()
        val height = CGImageGetHeight(cgImage).toDouble()
        val request = VNRecognizeTextRequest(completionHandler = null).apply {
            recognitionLevel = VNRequestTextRecognitionLevelAccurate
            recognitionLanguages = listOf("de-DE", "en-US")
            usesLanguageCorrection = false
        }
        perform(cgImage, request)
        val raw = request.results().orEmpty().filterIsInstance<VNRecognizedTextObservation>().map { observation ->
            val text = (observation.topCandidates(1u).firstOrNull() as? VNRecognizedText)?.string.orEmpty()
            // Vision's boxes are normalised with the origin bottom-left.
            val bounds = observation.boundingBox.useContents {
                OcrLineMapper.Bounds(
                    left = (origin.x * width).roundToInt(),
                    top = ((1.0 - origin.y - size.height) * height).roundToInt(),
                    right = ((origin.x + size.width) * width).roundToInt(),
                    bottom = ((1.0 - origin.y) * height).roundToInt(),
                )
            }
            OcrLineMapper.RawLine(text, bounds)
        }
        // Vision returns a label's name and each value column as separate observations; the
        // shared mapper puts them back in reading order, row by row, as on Android.
        OcrLineMapper.map(raw)
    }

    /**
     * The first product code (EAN-13, EAN-8, UPC-E) on [image], or `null`. Vision's default
     * detector does not find every code (it returns nothing in the simulator, and a photo cropped
     * tight to the bars has no quiet zone), so it falls back to the older detector revisions and
     * then to a copy with a white margin.
     */
    suspend fun detectBarcode(image: UIImage): String? = withContext(Dispatchers.IO) {
        listOf(image, withMargin(image)).firstNotNullOfOrNull { candidate ->
            val cgImage = candidate.CGImage ?: return@firstNotNullOfOrNull null
            BARCODE_REVISIONS.firstNotNullOfOrNull { revision -> detectBarcode(cgImage, revision) }
        }
    }

    private fun detectBarcode(image: CGImageRef, revision: ULong?): String? {
        val request = VNDetectBarcodesRequest(completionHandler = null).apply {
            if (revision != null) this.revision = revision
            symbologies = listOf(VNBarcodeSymbologyEAN13, VNBarcodeSymbologyEAN8, VNBarcodeSymbologyUPCE)
        }
        perform(image, request)
        val observations = request.results().orEmpty().filterIsInstance<VNBarcodeObservation>()
        if (isSimulator) NSLog("IosVision: barcode revision %s found %ld", revision?.toString() ?: "default", observations.size.toLong())
        return observations.firstNotNullOfOrNull { it.payloadStringValue }
    }

    /** [image] on a white canvas a fifth larger on every side. */
    private fun withMargin(image: UIImage): UIImage {
        val (width, height) = image.size.useContents { width to height }
        val margin = maxOf(width, height) / 5
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { scale = 1.0 }
        return UIGraphicsImageRenderer(CGSizeMake(width + 2 * margin, height + 2 * margin), format).imageWithActions { context ->
            UIColor.whiteColor.setFill()
            context?.fillRect(CGRectMake(0.0, 0.0, width + 2 * margin, height + 2 * margin))
            image.drawInRect(CGRectMake(margin, margin, width, height))
        }
    }

    /** `null` is the SDK's default revision; the others are tried newest first. */
    private val BARCODE_REVISIONS: List<ULong?> = listOf(
        null,
        VNDetectBarcodesRequestRevision4,
        VNDetectBarcodesRequestRevision3,
        VNDetectBarcodesRequestRevision2,
        VNDetectBarcodesRequestRevision1,
    ).map { it?.toULong() }

    /**
     * Runs [request] on [image]. The simulator has no Neural Engine and Vision's default device
     * fails there ("could not create inference context"), so it is pinned to the CPU; a failure
     * is logged and leaves the results empty.
     */
    @Suppress("DEPRECATION")
    private fun perform(image: CGImageRef, request: VNRequest) {
        if (isSimulator) request.usesCPUOnly = true
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val ok = VNImageRequestHandler(cGImage = image, options = emptyMap<Any?, Any>())
                .performRequests(listOf(request), error = error.ptr)
            if (!ok) NSLog("IosVision: %s", error.value?.localizedDescription ?: "request failed")
        }
    }

    private val isSimulator: Boolean
        get() = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null
}
