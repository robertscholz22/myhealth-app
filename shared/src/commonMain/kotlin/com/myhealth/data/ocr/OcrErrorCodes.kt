package com.myhealth.data.ocr

/**
 * The synthetic `AppError.Network.code`s of the label/barcode recognisers (P4.8), in common code so
 * `ScanMessages` can name them on every platform; the Android ML Kit mapping is `OcrErrors`.
 */
object OcrErrorCodes {

    /** The on-demand recognition model is not on the device yet — retry in a moment. */
    const val MODEL_NOT_READY = 1001

    /** Recognition failed for a reason a retry will not fix. */
    const val RECOGNITION_FAILED = 1002
}
