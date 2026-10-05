package com.myhealth.domain.util

/**
 * Single error type for repository/engine results (PLAN §1.5). Domain never formats a
 * user-facing string from this — that mapping lives in `ui/common/AppError.toMessage()`.
 */
sealed interface AppError {
    data class Storage(val cause: Throwable) : AppError
    data class Network(val code: Int?, val cause: Throwable?) : AppError
    data object HealthConnectUnavailable : AppError
    data object HealthConnectUpdateRequired : AppError
    data object HealthConnectPermissionDenied : AppError
    data class Parse(val what: String, val detail: String) : AppError
    data class Validation(val field: String, val message: String) : AppError
    data class Unexpected(val cause: Throwable) : AppError
}
