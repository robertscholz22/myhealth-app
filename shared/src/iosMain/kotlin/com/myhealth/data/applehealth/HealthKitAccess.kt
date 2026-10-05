package com.myhealth.data.applehealth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.HealthKit.HKAuthorizationRequestStatusUnnecessary
import platform.HealthKit.HKHealthStore
import kotlin.coroutines.resume

/**
 * Apple Health availability and the read-permission sheet (P22.1). HealthKit hides whether read
 * access was granted — an app only learns whether the sheet still has something to ask
 * ([wasAnswered]). Denied types simply return no data.
 */
@OptIn(ExperimentalForeignApi::class)
class HealthKitAccess(val store: HKHealthStore = HKHealthStore()) {

    val isAvailable: Boolean get() = HKHealthStore.isHealthDataAvailable()

    /** The identifiers the sheet asks for — shown as the "permissions" of the shared screen state. */
    val readIdentifiers: Set<String> by lazy { HealthKitTypes.read.map { it.identifier }.toSet() }

    /** True once the user has answered the sheet for every type MyHealth reads. */
    suspend fun wasAnswered(): Boolean {
        if (!isAvailable) return false
        return suspendCancellableCoroutine { cont ->
            store.getRequestStatusForAuthorizationToShareTypes(emptySet<Any>(), HealthKitTypes.read) { status, _ ->
                cont.resume(status == HKAuthorizationRequestStatusUnnecessary)
            }
        }
    }

    /** Shows the sheet (only types not answered yet); true when it completed without an error. */
    suspend fun request(): Boolean {
        if (!isAvailable) return false
        return suspendCancellableCoroutine { cont ->
            store.requestAuthorizationToShareTypes(null, HealthKitTypes.read) { success, _ -> cont.resume(success) }
        }
    }
}
