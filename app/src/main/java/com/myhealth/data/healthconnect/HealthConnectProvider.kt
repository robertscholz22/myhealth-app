package com.myhealth.data.healthconnect

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController

/** Availability of the Health Connect SDK on this device (PLAN P2.1). */
enum class HcStatus {
    /** The provider is installed and usable — [HealthConnectProvider.client] returns non-null. */
    AVAILABLE,

    /** The provider is installed but too old; the user must update it from the Play Store. */
    UPDATE_REQUIRED,

    /** No provider at all (or the platform does not support it). Sync must stay switched off. */
    UNAVAILABLE,
}

/**
 * The single place that touches [HealthConnectClient] construction and the permission contract
 * (PLAN P2.1). Everything above this class deals with [HcStatus], permission strings and the
 * plain DTOs of `HcDto.kt` — never with a Health Connect type.
 *
 * Held by `AppGraph`, so it must not keep an Activity reference: only the application [Context]
 * is stored.
 */
class HealthConnectProvider(private val context: Context) {

    /**
     * Maps `HealthConnectClient.getSdkStatus(context)`
     * (`SDK_AVAILABLE` / `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED` / `SDK_UNAVAILABLE`) to
     * [HcStatus]. Never throws: a device without the platform bits reports [HcStatus.UNAVAILABLE].
     */
    fun status(): HcStatus = try {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HcStatus.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcStatus.UPDATE_REQUIRED
            else -> HcStatus.UNAVAILABLE
        }
    } catch (e: Exception) {
        HcStatus.UNAVAILABLE
    }

    /**
     * The client, or `null` when the SDK is unavailable or the provider cannot be bound.
     * `getOrCreate` itself throws when the provider is missing, so the call is guarded twice.
     */
    fun client(): HealthConnectClient? {
        if (status() != HcStatus.AVAILABLE) return null
        return try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The permissions Health Connect currently reports as granted, or an empty set when the SDK
     * is unavailable or the query fails. Compare with [HcPermissions.REQUIRED_CORE].
     */
    suspend fun granted(): Set<String> {
        val client = client() ?: return emptySet()
        return try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Contract for `rememberLauncherForActivityResult`: launch it with [HcPermissions.ALL], the
     * result is the set that ended up granted.
     */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()
}
