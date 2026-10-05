package com.myhealth.data.ocr

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.myhealth.data.ocr.OcrErrors.await
import com.myhealth.domain.engine.label.OcrLine
import com.myhealth.domain.util.Outcome

/**
 * On-device text recognition for the label scanner (PLAN P4.8), using the **unbundled**
 * `play-services-mlkit-text-recognition` client (amendment A3): the same
 * `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` API, with the model fetched
 * by Play Services on first use — [OcrErrors.MODEL_NOT_READY] covers that window.
 *
 * The recogniser is a heavy object, so one instance is kept for the process lifetime (it is held
 * by `AppGraph`); [close] exists for symmetry and for tests.
 */
class MlKitTextSource(
    // Built on first use, not on construction: `getClient` reaches into Play Services, and a
    // failure there has to surface as a scan error, not as a crash while building `AppGraph`.
    provider: () -> TextRecognizer = { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) },
) {

    private val recognizer: TextRecognizer by lazy(provider)

    /** Recognises [image] and maps the result onto the parser's [OcrLine]s (§3.6 input). */
    suspend fun recognize(image: InputImage): Outcome<List<OcrLine>> = try {
        Outcome.Ok(OcrLineMapper.fromText(recognizer.process(image).await()))
    } catch (e: Exception) {
        Outcome.Err(OcrErrors.toAppError(e))
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
