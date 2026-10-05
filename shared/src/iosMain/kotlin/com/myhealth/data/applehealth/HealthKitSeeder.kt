package com.myhealth.data.applehealth

import com.myhealth.data.time.SystemPlatformClock
import com.myhealth.data.time.timeZone
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import platform.HealthKit.HKCategorySample
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuery
import platform.HealthKit.HKSource
import platform.HealthKit.HKWorkout
import platform.HealthKit.predicateForObjectsFromSource
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Debug-only test data for the simulator (P22.1), the iOS twin of `tools/hc-seeder`: [days] days
 * ending today of runs with heart rate and speed, a soccer and a strength session per week, steps,
 * energy, distance, resting heart rate, staged sleep, weight and body fat — all from one fixed
 * seed, so every run has the same shape. Started by the Swift app for the launch argument
 * `-seedHealthKit` in Debug builds only; a store that already holds this app's workouts is left
 * alone, so a relaunch does not double the data.
 */
@OptIn(ExperimentalForeignApi::class)
object HealthKitSeeder {

    private val store = HKHealthStore()

    /** Seeds in the background and reports a one-line result (shown in the app log). */
    fun seed(days: Int, onDone: (String) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            val result = runCatching { seedNow(days) }.getOrElse { "failed: ${it.message}" }
            println("HealthKitSeeder: $result")
            onDone(result)
        }
    }

    private val shareTypes: Set<HKObjectType>
        get() = with(HealthKitTypes) {
            setOf(
                workout, heartRate, steps, activeEnergy, basalEnergy, walkRunDistance, restingHeartRate, sleep,
                bodyMass, bodyFat, runningSpeed,
            )
        }

    private suspend fun seedNow(days: Int): String {
        val allowed = suspendCancellableCoroutine { cont ->
            store.requestAuthorizationToShareTypes(shareTypes, null) { ok, _ -> cont.resume(ok) }
        }
        if (!allowed) return "write access was not granted"
        val own = HKQuery.predicateForObjectsFromSource(HKSource.defaultSource())
        if (store.samples(HealthKitTypes.workout, own, limit = 1u).isNotEmpty()) return "already seeded"

        val zone = SystemPlatformClock.timeZone
        val now = Clock.System.now().toEpochMilliseconds()
        val today = Clock.System.now().toLocalDateTime(zone).date
        val rnd = Random(SEED)
        val objects = mutableListOf<Any>()
        var workouts = 0
        for (offset in days - 1 downTo 0) {
            val date = today.minus(DatePeriod(days = offset))
            objects.addAll(dailySamples(date, zone, rnd))
            objects.addAll(sleepSamples(date, zone))
            if (offset % 3 == 0) objects += quantity(HealthKitTypes.bodyMass, HealthKitUnits.kilogram, 72.0 + rnd.nextDouble(-0.5, 0.5), at(date, 7, 15, zone))
            if (offset % 7 == 0) objects += quantity(HealthKitTypes.bodyFat, HealthKitUnits.percent, 0.18, at(date, 7, 16, zone))
            val session = when (date.dayOfWeek.ordinal) {
                0, 2, 5 -> Session(AppleWorkoutType.RUNNING, 18, 0, rnd.nextInt(40, 61), 125..168, run = true)
                1 -> Session(AppleWorkoutType.SOCCER, 19, 0, 90, 100..176, run = false)
                3 -> Session(AppleWorkoutType.TRADITIONAL_STRENGTH_TRAINING, 18, 30, 45, 95..132, run = false)
                else -> null
            }
            if (session != null) {
                objects.addAll(workoutSamples(date, session, zone, rnd))
                workouts++
            }
        }
        val due = objects.filter { endOf(it) <= now }
        due.chunked(CHUNK).forEach { chunk ->
            val saved = suspendCancellableCoroutine { cont -> store.saveObjects(chunk) { ok, _ -> cont.resume(ok) } }
            if (!saved) return "saving failed"
        }
        return "seeded ${due.size} objects, $workouts workouts, $days days"
    }

    private data class Session(val type: Long, val hour: Int, val minute: Int, val minutes: Int, val hr: IntRange, val run: Boolean)

    private fun workoutSamples(date: LocalDate, s: Session, zone: TimeZone, rnd: Random): List<Any> {
        val start = at(date, s.hour, s.minute, zone)
        val end = start + s.minutes * MINUTE
        val out = mutableListOf<Any>()
        val distance = if (s.run) s.minutes * 60.0 / 330.0 * 1000.0 else null
        val energy = s.minutes * if (s.run) 11.0 else 8.0
        @Suppress("DEPRECATION")
        out += HKWorkout.workoutWithActivityType(
            s.type.toULong(),
            start.toNSDate(),
            end.toNSDate(),
            null,
            HKQuantity.quantityWithUnit(HealthKitUnits.kcal, energy),
            distance?.let { HKQuantity.quantityWithUnit(HealthKitUnits.meter, it) },
            null,
        )
        var t = start
        var i = 0
        val steps = s.minutes * 2
        while (t < end) {
            val progress = i.toDouble() / steps
            val bpm = s.hr.first + (s.hr.last - s.hr.first) * progress + rnd.nextDouble(-4.0, 4.0)
            out += quantity(HealthKitTypes.heartRate, HealthKitUnits.perMinute, bpm, t)
            if (s.run) out += quantity(HealthKitTypes.runningSpeed, HealthKitUnits.meterPerSecond, 3.03 + rnd.nextDouble(-0.25, 0.25), t)
            t += 30_000L
            i++
        }
        out += quantity(HealthKitTypes.activeEnergy, HealthKitUnits.kcal, energy, start, end)
        if (distance != null) out += quantity(HealthKitTypes.walkRunDistance, HealthKitUnits.meter, distance, start, end)
        return out
    }

    private fun dailySamples(date: LocalDate, zone: TimeZone, rnd: Random): List<Any> {
        val morning = at(date, 8, 0, zone)
        val evening = at(date, 17, 30, zone)
        val steps = rnd.nextInt(5_000, 12_001).toDouble()
        return listOf(
            quantity(HealthKitTypes.steps, HealthKitUnits.count, steps, morning, evening),
            quantity(HealthKitTypes.walkRunDistance, HealthKitUnits.meter, steps * 0.75, morning, evening),
            quantity(HealthKitTypes.activeEnergy, HealthKitUnits.kcal, rnd.nextDouble(250.0, 450.0), morning, evening),
            quantity(HealthKitTypes.basalEnergy, HealthKitUnits.kcal, 1_650.0, at(date, 0, 0, zone), at(date, 23, 59, zone)),
            quantity(HealthKitTypes.restingHeartRate, HealthKitUnits.perMinute, rnd.nextInt(48, 57).toDouble(), at(date, 7, 0, zone)),
        )
    }

    /** The night ending on [date]: 23:00 → 06:30 in four cycles. */
    private fun sleepSamples(date: LocalDate, zone: TimeZone): List<Any> {
        val pattern = listOf(
            AppleSleepValue.ASLEEP_CORE to 40, AppleSleepValue.ASLEEP_DEEP to 50, AppleSleepValue.ASLEEP_CORE to 25,
            AppleSleepValue.ASLEEP_REM to 20, AppleSleepValue.AWAKE to 5, AppleSleepValue.ASLEEP_CORE to 45,
            AppleSleepValue.ASLEEP_DEEP to 35, AppleSleepValue.ASLEEP_REM to 30, AppleSleepValue.ASLEEP_CORE to 50,
            AppleSleepValue.ASLEEP_REM to 35, AppleSleepValue.AWAKE to 5, AppleSleepValue.ASLEEP_CORE to 40,
            AppleSleepValue.ASLEEP_REM to 70,
        )
        var t = at(date.plus(DatePeriod(days = -1)), 23, 0, zone)
        return pattern.map { (value, minutes) ->
            val end = t + minutes * MINUTE
            HKCategorySample.categorySampleWithType(HealthKitTypes.sleep, value, t.toNSDate(), end.toNSDate()).also { t = end }
        }
    }

    private fun quantity(type: HKQuantityType, unit: platform.HealthKit.HKUnit, value: Double, start: Long, end: Long = start): HKQuantitySample =
        HKQuantitySample.quantitySampleWithType(type, HKQuantity.quantityWithUnit(unit, value), start.toNSDate(), end.toNSDate())

    private fun endOf(obj: Any): Long = (obj as platform.HealthKit.HKSample).endDate.toMillis()

    private fun at(date: LocalDate, hour: Int, minute: Int, zone: TimeZone): Long =
        LocalDateTime(date, LocalTime(hour, minute)).toInstant(zone).toEpochMilliseconds()

    private const val SEED = 22L
    private const val MINUTE = 60_000L
    private const val CHUNK = 400
}
