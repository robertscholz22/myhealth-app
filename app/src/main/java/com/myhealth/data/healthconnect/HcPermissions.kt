package com.myhealth.data.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord

/**
 * The Health Connect permission strings this app asks for (PLAN P2.1). Every entry here has a
 * matching `<uses-permission>` in `AndroidManifest.xml` — Health Connect silently ignores a
 * request for a permission the manifest does not declare, so the two lists must stay in sync.
 *
 * Read-only: the app never writes to Health Connect. Exercise routes are deliberately absent
 * (amendment A6) — GPS tracks come from the FIT import (P7).
 */
object HcPermissions {

    /**
     * The permissions sync cannot work without: without these the exercise/daily/sleep/body
     * readers (P2.2) have nothing to read. Requested as one batch.
     */
    val REQUIRED_CORE: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
        HealthPermission.getReadPermission(StepsCadenceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
    )

    /**
     * The per-session power and pedalling-cadence channels (P12). **Not** in [REQUIRED_CORE]: a
     * phone upgrading from 1.0.x has not granted them, and sync must keep working — `HcReader`
     * degrades each read to an empty list on `SecurityException`. They are part of [ALL] so the
     * permission sheet offers them.
     *
     * Verified against connect-client 1.1.0 (`javap` on the AAR): `PowerRecord` maps to
     * `android.permission.health.READ_POWER`, but `CyclingPedalingCadenceRecord` maps to
     * `android.permission.health.READ_EXERCISE` — there is no separate
     * `READ_CYCLING_PEDALING_CADENCE` permission. So this set adds exactly one new string to
     * [ALL]; pedalling cadence rides along with the workout permission that is already required.
     */
    val OPTIONAL_DETAIL: Set<String> = setOf(
        HealthPermission.getReadPermission(PowerRecord::class),
        HealthPermission.getReadPermission(CyclingPedalingCadenceRecord::class),
    )

    /**
     * Grants access to data older than 30 days. Optional: without it the first backfill simply
     * stops at the 30-day wall instead of failing.
     */
    const val HISTORY: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /**
     * Lets the WorkManager sync (P2.7) read while the app is in the background. Optional: without
     * it sync still runs, but only while the app is in the foreground.
     */
    const val BACKGROUND: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** Everything the permission request contract is launched with. */
    val ALL: Set<String> = REQUIRED_CORE + OPTIONAL_DETAIL + HISTORY + BACKGROUND

    /** True when every [REQUIRED_CORE] permission is present in [granted]. */
    fun hasRequiredCore(granted: Set<String>): Boolean = granted.containsAll(REQUIRED_CORE)

    /** The [REQUIRED_CORE] permissions still missing from [granted]. */
    fun missingRequiredCore(granted: Set<String>): Set<String> = REQUIRED_CORE - granted
}
