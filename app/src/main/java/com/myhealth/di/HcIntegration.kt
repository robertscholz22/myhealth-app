package com.myhealth.di

import androidx.activity.result.contract.ActivityResultContract
import com.myhealth.data.healthconnect.HcPermissions
import com.myhealth.data.healthconnect.HealthConnectProvider
import com.myhealth.data.healthconnect.HcStatus as DataHcStatus

/** [HcIntegration] backed by the real [HealthConnectProvider] held in [AppGraph]. */
class HealthConnectIntegration(private val provider: HealthConnectProvider) : HcIntegration {

    override fun status(): HcStatus = when (provider.status()) {
        DataHcStatus.AVAILABLE -> HcStatus.AVAILABLE
        DataHcStatus.UPDATE_REQUIRED -> HcStatus.UPDATE_REQUIRED
        DataHcStatus.UNAVAILABLE -> HcStatus.UNAVAILABLE
    }

    override suspend fun granted(): Set<String> = provider.granted()

    override val allPermissions: Set<String> = HcPermissions.ALL

    override val optionalDetailPermissions: Set<String> = HcPermissions.OPTIONAL_DETAIL

    /** The Health Connect permission request, launched by `AndroidPlatformUi`. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        provider.permissionContract()
}
