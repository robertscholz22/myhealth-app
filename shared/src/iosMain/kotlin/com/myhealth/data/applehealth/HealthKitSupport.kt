package com.myhealth.data.applehealth

import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSNumber
import platform.Foundation.NSPredicate
import platform.Foundation.NSSortDescriptor
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.HealthKit.HKAnchoredObjectQuery
import platform.HealthKit.HKCategoryType
import platform.HealthKit.HKCategoryTypeIdentifierSleepAnalysis
import platform.HealthKit.HKDeletedObject
import platform.HealthKit.HKErrorAuthorizationDenied
import platform.HealthKit.HKErrorAuthorizationNotDetermined
import platform.HealthKit.HKErrorDatabaseInaccessible
import platform.HealthKit.HKErrorDomain
import platform.HealthKit.HKErrorHealthDataUnavailable
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectQueryNoLimit
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuantityTypeIdentifierActiveEnergyBurned
import platform.HealthKit.HKQuantityTypeIdentifierBasalEnergyBurned
import platform.HealthKit.HKQuantityTypeIdentifierBodyFatPercentage
import platform.HealthKit.HKQuantityTypeIdentifierBodyMass
import platform.HealthKit.HKQuantityTypeIdentifierCyclingCadence
import platform.HealthKit.HKQuantityTypeIdentifierCyclingPower
import platform.HealthKit.HKQuantityTypeIdentifierCyclingSpeed
import platform.HealthKit.HKQuantityTypeIdentifierDistanceCycling
import platform.HealthKit.HKQuantityTypeIdentifierDistanceWalkingRunning
import platform.HealthKit.HKQuantityTypeIdentifierFlightsClimbed
import platform.HealthKit.HKQuantityTypeIdentifierHeartRate
import platform.HealthKit.HKQuantityTypeIdentifierOxygenSaturation
import platform.HealthKit.HKQuantityTypeIdentifierRespiratoryRate
import platform.HealthKit.HKQuantityTypeIdentifierRestingHeartRate
import platform.HealthKit.HKQuantityTypeIdentifierRunningPower
import platform.HealthKit.HKQuantityTypeIdentifierRunningSpeed
import platform.HealthKit.HKQuantityTypeIdentifierStepCount
import platform.HealthKit.HKQuantityTypeIdentifierVO2Max
import platform.HealthKit.HKQueryAnchor
import platform.HealthKit.HKSample
import platform.HealthKit.HKSampleQuery
import platform.HealthKit.HKSampleSortIdentifierStartDate
import platform.HealthKit.HKSampleType
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkoutType
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

/** The HealthKit types MyHealth reads (P22.1), by identifier — the iOS counterpart of `HcPermissions`. */
@OptIn(ExperimentalForeignApi::class)
internal object HealthKitTypes {
    fun quantity(identifier: String?): HKQuantityType = requireNotNull(HKObjectType.quantityTypeForIdentifier(identifier)) {
        "Unknown HealthKit quantity type $identifier"
    }

    val workout: HKWorkoutType get() = HKObjectType.workoutType()
    val sleep: HKCategoryType get() = requireNotNull(HKObjectType.categoryTypeForIdentifier(HKCategoryTypeIdentifierSleepAnalysis))

    val heartRate get() = quantity(HKQuantityTypeIdentifierHeartRate)
    val restingHeartRate get() = quantity(HKQuantityTypeIdentifierRestingHeartRate)
    val steps get() = quantity(HKQuantityTypeIdentifierStepCount)
    val activeEnergy get() = quantity(HKQuantityTypeIdentifierActiveEnergyBurned)
    val basalEnergy get() = quantity(HKQuantityTypeIdentifierBasalEnergyBurned)
    val walkRunDistance get() = quantity(HKQuantityTypeIdentifierDistanceWalkingRunning)
    val cyclingDistance get() = quantity(HKQuantityTypeIdentifierDistanceCycling)
    val flights get() = quantity(HKQuantityTypeIdentifierFlightsClimbed)
    val oxygenSaturation get() = quantity(HKQuantityTypeIdentifierOxygenSaturation)
    val respiratoryRate get() = quantity(HKQuantityTypeIdentifierRespiratoryRate)
    val vo2Max get() = quantity(HKQuantityTypeIdentifierVO2Max)
    val runningSpeed get() = quantity(HKQuantityTypeIdentifierRunningSpeed)
    val runningPower get() = quantity(HKQuantityTypeIdentifierRunningPower)
    val cyclingSpeed get() = quantity(HKQuantityTypeIdentifierCyclingSpeed)
    val cyclingPower get() = quantity(HKQuantityTypeIdentifierCyclingPower)
    val cyclingCadence get() = quantity(HKQuantityTypeIdentifierCyclingCadence)
    val bodyMass get() = quantity(HKQuantityTypeIdentifierBodyMass)
    val bodyFat get() = quantity(HKQuantityTypeIdentifierBodyFatPercentage)

    /** The daily statistics and how each is aggregated per day (sum or average). */
    val daily: List<Triple<AppleDailyMetric, HKQuantityType, Boolean>>
        get() = listOf(
            Triple(AppleDailyMetric.STEPS, steps, true),
            Triple(AppleDailyMetric.ACTIVE_KCAL, activeEnergy, true),
            Triple(AppleDailyMetric.BASAL_KCAL, basalEnergy, true),
            Triple(AppleDailyMetric.DISTANCE_M, walkRunDistance, true),
            Triple(AppleDailyMetric.FLOORS, flights, true),
            Triple(AppleDailyMetric.RESTING_HR, restingHeartRate, false),
            Triple(AppleDailyMetric.SPO2_FRACTION, oxygenSaturation, false),
            Triple(AppleDailyMetric.RESPIRATORY_RATE, respiratoryRate, false),
            Triple(AppleDailyMetric.VO2_MAX, vo2Max, false),
        )

    val body: List<HKQuantityType> get() = listOf(bodyMass, bodyFat)

    /** Everything the read-permission sheet asks for. */
    val read: Set<HKObjectType>
        get() = setOf(
            workout, sleep, heartRate, restingHeartRate, steps, activeEnergy, basalEnergy, walkRunDistance,
            cyclingDistance, flights, oxygenSaturation, respiratoryRate, vo2Max, runningSpeed, runningPower,
            cyclingSpeed, cyclingPower, cyclingCadence, bodyMass, bodyFat,
        )
}

/** HealthKit units by their unit strings. */
@OptIn(ExperimentalForeignApi::class)
internal object HealthKitUnits {
    val count: HKUnit get() = HKUnit.unitFromString("count")
    val perMinute: HKUnit get() = HKUnit.unitFromString("count/min")
    val kcal: HKUnit get() = HKUnit.unitFromString("kcal")
    val meter: HKUnit get() = HKUnit.unitFromString("m")
    val meterPerSecond: HKUnit get() = HKUnit.unitFromString("m/s")
    val watt: HKUnit get() = HKUnit.unitFromString("W")
    val kilogram: HKUnit get() = HKUnit.unitFromString("kg")
    val percent: HKUnit get() = HKUnit.unitFromString("%")
    val vo2: HKUnit get() = HKUnit.unitFromString("ml/kg*min")
}

internal fun Long.toNSDate(): NSDate = NSDate.dateWithTimeIntervalSince1970(this / 1000.0)

internal fun NSDate.toMillis(): Long = (timeIntervalSince1970 * 1000.0).roundToLong()

internal fun HKQuantity?.valueIn(unit: HKUnit): Double? =
    this?.takeIf { it.isCompatibleWithUnit(unit) }?.doubleValueForUnit(unit)

/** A HealthKit metadata flag (`NSNumber` bool) → Kotlin. */
internal fun Any?.metadataBoolean(): Boolean? = when (this) {
    is Boolean -> this
    is NSNumber -> boolValue
    else -> null
}

internal fun Any?.metadataLong(): Long? = when (this) {
    is Long -> this
    is Int -> toLong()
    is NSNumber -> longLongValue
    else -> null
}

/** A failed HealthKit call, kept so [toAppError] can sort it into the shared error kinds. */
internal class HealthKitException(val error: NSError) : Exception(error.localizedDescription)

/**
 * HealthKit errors → [AppError] with the Health Connect reader's semantics: a locked or missing
 * store is transient ([AppError.HealthConnectUnavailable], retried), missing authorization is
 * [AppError.HealthConnectPermissionDenied], anything else unexpected.
 */
internal fun Throwable.toAppError(): AppError {
    val error = (this as? HealthKitException)?.error ?: return AppError.Unexpected(this)
    if (error.domain != HKErrorDomain) return AppError.Unexpected(this)
    return when (error.code) {
        HKErrorHealthDataUnavailable, HKErrorDatabaseInaccessible -> AppError.HealthConnectUnavailable
        HKErrorAuthorizationDenied, HKErrorAuthorizationNotDetermined -> AppError.HealthConnectPermissionDenied
        else -> AppError.Unexpected(this)
    }
}

internal suspend fun <T> healthKitCall(block: suspend () -> T): Outcome<T> = try {
    Outcome.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Outcome.Err(e.toAppError())
}

/** Runs an `HKSampleQuery`, oldest first. */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun HKHealthStore.samples(
    type: HKSampleType,
    predicate: NSPredicate?,
    limit: ULong = HKObjectQueryNoLimit,
): List<HKSample> = suspendCancellableCoroutine { cont ->
    val sort = NSSortDescriptor.sortDescriptorWithKey(HKSampleSortIdentifierStartDate, ascending = true)
    val query = HKSampleQuery(type, predicate, limit, listOf(sort)) { _, results, error ->
        if (error != null) {
            cont.resumeWithException(HealthKitException(error))
        } else {
            cont.resume(results.orEmpty().filterIsInstance<HKSample>())
        }
    }
    cont.invokeOnCancellation { stopQuery(query) }
    executeQuery(query)
}

/** One page of an `HKAnchoredObjectQuery`. */
internal class AnchoredPage(
    val added: List<HKSample>,
    val deleted: List<HKDeletedObject>,
    val anchor: HKQueryAnchor?,
)

@OptIn(ExperimentalForeignApi::class)
internal suspend fun HKHealthStore.anchored(
    type: HKSampleType,
    predicate: NSPredicate?,
    anchor: HKQueryAnchor?,
    limit: ULong,
): AnchoredPage = suspendCancellableCoroutine { cont ->
    val query = HKAnchoredObjectQuery(type, predicate, anchor, limit) { _, added, deleted, newAnchor, error ->
        if (error != null) {
            cont.resumeWithException(HealthKitException(error))
        } else {
            cont.resume(
                AnchoredPage(
                    added = added.orEmpty().filterIsInstance<HKSample>(),
                    deleted = deleted.orEmpty().filterIsInstance<HKDeletedObject>(),
                    anchor = newAnchor,
                ),
            )
        }
    }
    cont.invokeOnCancellation { stopQuery(query) }
    executeQuery(query)
}

/** `HKQueryAnchor` ↔ base64 of its secure keyed archive. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object AnchorCodec {
    fun encode(anchor: HKQueryAnchor): String? =
        NSKeyedArchiver.archivedDataWithRootObject(anchor, requiringSecureCoding = true, error = null)
            ?.base64EncodedStringWithOptions(0u)

    fun decode(text: String): HKQueryAnchor? {
        val data = NSData.create(base64EncodedString = text, options = 0u) ?: return null
        return NSKeyedUnarchiver.unarchivedObjectOfClass(HKQueryAnchor, fromData = data, error = null) as? HKQueryAnchor
    }
}
