package com.myhealth.data.ocr

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.myhealth.domain.util.AppError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit failure mapping and the one Play-Services `Task` bridge the OCR sources need (P4.8).
 *
 * The unbundled ML Kit artifacts (amendment A3) download their models through Play Services on
 * first use, so the very first scan can fail with a *retryable* error; those codes are mapped to
 * [AppError.Network] carrying [MODEL_NOT_READY] so the UI can say "try again in a moment" instead
 * of "something went wrong". `kotlinx-coroutines-play-services` is not in the version catalog
 * (R4/R5), hence the hand-written [await].
 */
object OcrErrors {

    /** Synthetic `AppError.Network.code`: the on-demand model is not on the device yet. */
    const val MODEL_NOT_READY = OcrErrorCodes.MODEL_NOT_READY

    /** Synthetic `AppError.Network.code`: ML Kit failed for a reason a retry will not fix. */
    const val RECOGNITION_FAILED = OcrErrorCodes.RECOGNITION_FAILED

    /** ML Kit codes that mean "the optional module is still being fetched / is unavailable". */
    private val RETRYABLE = setOf(
        MlKitException.UNAVAILABLE,
        MlKitException.FAILED_PRECONDITION,
        MlKitException.NOT_FOUND,
        MlKitException.NETWORK_ISSUE,
    )

    fun toAppError(cause: Throwable): AppError = when {
        cause is MlKitException && cause.errorCode in RETRYABLE ->
            AppError.Network(MODEL_NOT_READY, cause)
        cause is MlKitException -> AppError.Network(RECOGNITION_FAILED, cause)
        else -> AppError.Unexpected(if (cause is Exception) cause else RuntimeException(cause))
    }

    /**
     * Awaits a Play-Services [Task] without `kotlinx-coroutines-play-services`: one listener per
     * outcome, cancellation propagated back to the coroutine.
     */
    suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }
}
