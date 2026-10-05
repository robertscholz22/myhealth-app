package com.myhealth.seeder

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Velocity
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.reflect.KClass

private const val TAG = "HcSeeder"
private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
private const val MAX_BATCH = 500

/**
 * Writes / clears deterministic, Garmin-like Health Connect data for MyHealth's sync path to be
 * exercised against. Everything is generated from a single [Random] seeded once per run, walked
 * strictly in chronological, single-threaded order so the same (seed, days) always produces the
 * same data.
 */
class Seeder(context: Context) {

    private val client: HealthConnectClient = HealthConnectClient.getOrCreate(context)

    private val allTypes: List<KClass<out Record>> = listOf(
        ExerciseSessionRecord::class,
        HeartRateRecord::class,
        DistanceRecord::class,
        SpeedRecord::class,
        ActiveCaloriesBurnedRecord::class,
        TotalCaloriesBurnedRecord::class,
        StepsCadenceRecord::class,
        PowerRecord::class,
        CyclingPedalingCadenceRecord::class,
        StepsRecord::class,
        RestingHeartRateRecord::class,
        FloorsClimbedRecord::class,
        SleepSessionRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        OxygenSaturationRecord::class,
        RespiratoryRateRecord::class,
        WeightRecord::class,
        BodyFatRecord::class,
        Vo2MaxRecord::class,
    )

    suspend fun clear() {
        // Generous window: covers anything this seeder could plausibly have written.
        val filter = TimeRangeFilter.between(Instant.now().minus(Duration.ofDays(400)), Instant.now().plus(Duration.ofDays(1)))
        for (type in allTypes) {
            try {
                client.deleteRecords(type, filter)
                Log.i(TAG, "clear: deleted ${type.simpleName}")
            } catch (e: SecurityException) {
                Log.w(TAG, "clear: skipped ${type.simpleName}, no permission: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "clear: failed ${type.simpleName}: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "clear: failed ${type.simpleName}: ${e.message}")
            }
        }
    }

    suspend fun seed(days: Int, seed: Long): Map<String, Int> {
        val rnd = Random(seed)
        val meta = Metadata.manualEntry()

        val today = LocalDate.now(BERLIN)
        val end = today.minusDays(1)
        val start = end.minusDays((days - 1).toLong())

        val exercises = mutableListOf<ExerciseSessionRecord>()
        val heartRate = mutableListOf<HeartRateRecord>()
        val distance = mutableListOf<DistanceRecord>()
        val speed = mutableListOf<SpeedRecord>()
        val activeCal = mutableListOf<ActiveCaloriesBurnedRecord>()
        val totalCal = mutableListOf<TotalCaloriesBurnedRecord>()
        val cadence = mutableListOf<StepsCadenceRecord>()
        val steps = mutableListOf<StepsRecord>()
        val restingHr = mutableListOf<RestingHeartRateRecord>()
        val floors = mutableListOf<FloorsClimbedRecord>()
        val sleep = mutableListOf<SleepSessionRecord>()
        val hrv = mutableListOf<HeartRateVariabilityRmssdRecord>()
        val spo2 = mutableListOf<OxygenSaturationRecord>()
        val resp = mutableListOf<RespiratoryRateRecord>()
        val weight = mutableListOf<WeightRecord>()
        val bodyFat = mutableListOf<BodyFatRecord>()
        val vo2max = mutableListOf<Vo2MaxRecord>()
        val rides = RideLists()

        // Pick which Saturday runs are 5k PRs (every 3rd Saturday in range) and which run day is
        // the single 10k in the middle of the range.
        val saturdays = generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .filter { it.dayOfWeek == DayOfWeek.SATURDAY }
            .toList()
        val prSaturdays = saturdays.filterIndexed { idx, _ -> (idx + 1) % 3 == 0 }.toSet()

        val runDays = generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .filter { it.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY) }
            .toList()
        val tenKDay = if (runDays.isNotEmpty()) runDays[runDays.size / 2] else null

        var dateIdx = 0
        var date = start
        val totalDays = (Duration.between(start.atStartOfDay(), end.atStartOfDay()).toDays() + 1).toInt().coerceAtLeast(1)
        while (!date.isAfter(end)) {
            val isMatchDayYesterday = date.minusDays(1).dayOfWeek == DayOfWeek.SUNDAY
            var dayActiveKcal = 0.0

            when (date.dayOfWeek) {
                DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY -> {
                    val isWed = date.dayOfWeek == DayOfWeek.WEDNESDAY
                    val isPrSat = date in prSaturdays
                    val is10k = date == tenKDay && !isPrSat
                    val distanceKm: Double
                    val durationMin: Double
                    if (isPrSat) {
                        distanceKm = 5.00
                        durationMin = 20.0 + rnd.nextDouble() * 2.0 // 20-22 min
                    } else if (is10k) {
                        distanceKm = 10.0
                        val paceSecPerKm = 300 + rnd.nextInt(41) // 300-340
                        durationMin = distanceKm * paceSecPerKm / 60.0
                    } else {
                        durationMin = (35 + rnd.nextInt(36)).toDouble() // 35-70
                        val paceSecPerKm = 300 + rnd.nextInt(41)
                        distanceKm = durationMin * 60.0 / paceSecPerKm
                    }
                    val startTime = zdt(date, LocalTime.of(18, 0))
                    val endTime = startTime.plusSeconds((durationMin * 60).toLong())
                    addRun(
                        rnd, meta, startTime, endTime, distanceKm, isInterval = isWed,
                        exercises, heartRate, distance, speed, activeCal, totalCal, cadence,
                    )
                    dayActiveKcal += activeKcalFor(durationMin, 11.5)
                }
                else -> {}
            }
            if (date.dayOfWeek == DayOfWeek.TUESDAY) {
                addTrainerRide(BERLIN, rnd, meta, zdt(date, LocalTime.of(6, 30)), exercises, activeCal, totalCal, rides)
                dayActiveKcal += 888.0
            }
            if (date.dayOfWeek == DayOfWeek.SATURDAY) {
                addOutdoorRide(
                    BERLIN, rnd, meta, zdt(date, LocalTime.of(10, 0)),
                    exercises, heartRate, distance, speed, activeCal, totalCal,
                )
                dayActiveKcal += activeKcalFor(75.0, 10.0)
            }
            if (date.dayOfWeek == DayOfWeek.TUESDAY) {
                val startTime = zdt(date, LocalTime.of(19, 0))
                val endTime = startTime.plusSeconds(90 * 60L)
                addSimpleSession(
                    rnd, meta, startTime, endTime, ExerciseSessionRecord.EXERCISE_TYPE_SOCCER, "Training",
                    hrLow = 120, hrHigh = 170, kcalPerMin = 9.0,
                    exercises, heartRate, activeCal, totalCal,
                )
                dayActiveKcal += activeKcalFor(90.0, 9.0)
            }
            if (date.dayOfWeek == DayOfWeek.THURSDAY) {
                val startTime = zdt(date, LocalTime.of(18, 30))
                val endTime = startTime.plusSeconds(55 * 60L)
                addSimpleSession(
                    rnd, meta, startTime, endTime, ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, "Kraft",
                    hrLow = 100, hrHigh = 140, kcalPerMin = 6.5,
                    exercises, heartRate, activeCal, totalCal,
                )
                dayActiveKcal += activeKcalFor(55.0, 6.5)
            }
            if (date.dayOfWeek == DayOfWeek.SUNDAY) {
                val startTime = zdt(date, LocalTime.of(15, 0))
                val endTime = startTime.plusSeconds(95 * 60L)
                addSimpleSession(
                    rnd, meta, startTime, endTime, ExerciseSessionRecord.EXERCISE_TYPE_SOCCER, "Spiel",
                    hrLow = 130, hrHigh = 185, kcalPerMin = 10.5,
                    exercises, heartRate, activeCal, totalCal,
                )
                dayActiveKcal += activeKcalFor(95.0, 10.5)
            }

            // --- Daily vitals & aggregates ---
            val dayStart = zdt(date, LocalTime.MIDNIGHT)
            val dayEnd = zdt(date.plusDays(1), LocalTime.MIDNIGHT)

            // Steps: 6000-14000 total split into 2-3 chunks.
            val totalSteps = 6000 + rnd.nextInt(8001)
            val chunkCount = 2 + rnd.nextInt(2)
            val chunkWindows = listOf(
                LocalTime.of(7, 30) to LocalTime.of(9, 0),
                LocalTime.of(12, 0) to LocalTime.of(13, 30),
                LocalTime.of(17, 0) to LocalTime.of(20, 30),
            ).shuffled(java.util.Random(rnd.nextLong())).take(chunkCount).sortedBy { it.first }
            var remaining = totalSteps
            chunkWindows.forEachIndexed { i, (from, to) ->
                val portion = if (i == chunkWindows.lastIndex) remaining else (totalSteps / chunkCount)
                remaining -= portion
                if (portion > 0) {
                    steps.add(StepsRecord(zdt(date, from), off(date, from), zdt(date, to), off(date, to), portion.toLong(), meta))
                }
            }

            val bmr = 1700.0
            val neat = if (dayActiveKcal > 0) 150 + rnd.nextInt(151) else 300 + rnd.nextInt(201)
            val dayTotalKcal = bmr + dayActiveKcal + neat
            totalCal.add(TotalCaloriesBurnedRecord(dayStart, off(date, LocalTime.MIDNIGHT), dayEnd, off(date.plusDays(1), LocalTime.MIDNIGHT), Energy.kilocalories(dayTotalKcal), meta))
            activeCal.add(ActiveCaloriesBurnedRecord(dayStart, off(date, LocalTime.MIDNIGHT), dayEnd, off(date.plusDays(1), LocalTime.MIDNIGHT), Energy.kilocalories(dayActiveKcal + neat * 0.3), meta))

            // Resting HR at 07:00, +4 the morning after a Sunday match.
            val rhr = (50 + rnd.nextInt(9)) + if (isMatchDayYesterday) 4 else 0
            restingHr.add(RestingHeartRateRecord(zdt(date, LocalTime.of(7, 0)), off(date, LocalTime.of(7, 0)), rhr.toLong(), meta))

            // Floors climbed.
            floors.add(FloorsClimbedRecord(dayStart, off(date, LocalTime.MIDNIGHT), dayEnd, off(date.plusDays(1), LocalTime.MIDNIGHT), (5 + rnd.nextInt(21)).toDouble(), meta))

            // Sleep: previous night 23:15 (+/-30min) -> this morning 06:45 (+/-30min).
            val sleepStartJitter = rnd.nextInt(61) - 30
            val sleepEndJitter = rnd.nextInt(61) - 30
            val sleepStart = zdt(date.minusDays(1), LocalTime.of(23, 15)).plusSeconds(sleepStartJitter * 60L)
            val sleepEnd = zdt(date, LocalTime.of(6, 45)).plusSeconds(sleepEndJitter * 60L)
            sleep.add(buildSleepSession(rnd, meta, sleepStart, sleepEnd, off(date.minusDays(1), LocalTime.of(23, 15)), off(date, LocalTime.of(6, 45))))

            // Morning vitals.
            hrv.add(HeartRateVariabilityRmssdRecord(zdt(date, LocalTime.of(6, 50)), off(date, LocalTime.of(6, 50)), 40.0 + rnd.nextDouble() * 30.0, meta))
            spo2.add(OxygenSaturationRecord(zdt(date, LocalTime.of(6, 55)), off(date, LocalTime.of(6, 55)), Percentage(96.0 + rnd.nextDouble() * 3.0), meta))
            resp.add(RespiratoryRateRecord(zdt(date, LocalTime.of(6, 58)), off(date, LocalTime.of(6, 58)), 13.0 + rnd.nextDouble() * 3.0, meta))

            // Body composition.
            if (dateIdx % 3 == 0) {
                val progress = if (totalDays <= 1) 0.0 else dateIdx.toDouble() / (totalDays - 1)
                val trend = 79.0 - progress * 1.5
                val noisy = trend + (rnd.nextDouble() - 0.5) * 0.6
                weight.add(WeightRecord(zdt(date, LocalTime.of(7, 5)), off(date, LocalTime.of(7, 5)), Mass.kilograms(noisy), meta))
            }
            if (dateIdx % 6 == 0) {
                bodyFat.add(BodyFatRecord(zdt(date, LocalTime.of(7, 6)), off(date, LocalTime.of(7, 6)), Percentage(17.0 + rnd.nextDouble() * 2.0), meta))
            }

            dateIdx++
            date = date.plusDays(1)
        }

        // One Vo2Max reading ~10 days ago, clamped into range.
        val vo2Date = today.minusDays(10).let { if (it.isBefore(start)) start else if (it.isAfter(end)) end else it }
        vo2max.add(Vo2MaxRecord(zdt(vo2Date, LocalTime.of(8, 0)), off(vo2Date, LocalTime.of(8, 0)), meta, 50.0, Vo2MaxRecord.MEASUREMENT_METHOD_OTHER))

        val counts = LinkedHashMap<String, Int>()
        counts["Exercise"] = insertType(exercises)
        counts["HeartRate"] = insertType(heartRate)
        counts["Distance"] = insertType(distance)
        counts["Speed"] = insertType(speed)
        counts["ActiveCalories"] = insertType(activeCal)
        counts["TotalCalories"] = insertType(totalCal)
        counts["StepsCadence"] = insertType(cadence)
        counts["Power"] = insertType(rides.power)
        counts["PedalCadence"] = insertType(rides.pedalCadence)
        counts["Steps"] = insertType(steps)
        counts["RestingHeartRate"] = insertType(restingHr)
        counts["FloorsClimbed"] = insertType(floors)
        counts["Sleep"] = insertType(sleep)
        counts["Hrv"] = insertType(hrv)
        counts["OxygenSaturation"] = insertType(spo2)
        counts["RespiratoryRate"] = insertType(resp)
        counts["Weight"] = insertType(weight)
        counts["BodyFat"] = insertType(bodyFat)
        counts["Vo2Max"] = insertType(vo2max)
        return counts
    }

    private suspend fun insertType(records: List<Record>): Int {
        if (records.isEmpty()) return 0
        val typeName = records.first()::class.simpleName
        var inserted = 0
        for (chunk in records.chunked(MAX_BATCH)) {
            try {
                val resp = client.insertRecords(chunk)
                inserted += resp.recordIdsList.size
            } catch (e: SecurityException) {
                Log.w(TAG, "insert: skipped $typeName, no permission: ${e.message}")
                return inserted
            } catch (e: IllegalStateException) {
                Log.w(TAG, "insert: failed $typeName: ${e.message}")
                return inserted
            } catch (e: Exception) {
                Log.w(TAG, "insert: failed $typeName: ${e.message}")
                return inserted
            }
        }
        Log.i(TAG, "insert: $typeName x$inserted")
        return inserted
    }

    // ---- helpers ----

    private fun zdt(date: LocalDate, time: LocalTime): Instant = ZonedDateTime.of(date, time, BERLIN).toInstant()
    private fun off(date: LocalDate, time: LocalTime) = BERLIN.rules.getOffset(LocalDateTime.of(date, time))

    private fun activeKcalFor(durationMin: Double, kcalPerMin: Double) = durationMin * kcalPerMin

    private fun addRun(
        rnd: Random,
        meta: Metadata,
        startTime: Instant,
        endTime: Instant,
        distanceKm: Double,
        isInterval: Boolean,
        exercises: MutableList<ExerciseSessionRecord>,
        heartRate: MutableList<HeartRateRecord>,
        distance: MutableList<DistanceRecord>,
        speed: MutableList<SpeedRecord>,
        activeCal: MutableList<ActiveCaloriesBurnedRecord>,
        totalCal: MutableList<TotalCaloriesBurnedRecord>,
        cadence: MutableList<StepsCadenceRecord>,
    ) {
        val startOffset = BERLIN.rules.getOffset(LocalDateTime.ofInstant(startTime, BERLIN))
        val endOffset = BERLIN.rules.getOffset(LocalDateTime.ofInstant(endTime, BERLIN))
        val durationSec = Duration.between(startTime, endTime).seconds

        exercises.add(
            ExerciseSessionRecord(
                startTime = startTime,
                startZoneOffset = startOffset,
                endTime = endTime,
                endZoneOffset = endOffset,
                metadata = meta,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                title = "Lauf",
            )
        )

        val hrSamples = mutableListOf<HeartRateRecord.Sample>()
        var t = 0L
        val warmupSec = minOf(360L, durationSec / 4)
        while (t <= durationSec) {
            val warm = if (t < warmupSec && warmupSec > 0) t.toDouble() / warmupSec else 1.0
            var hr = 130 + 30 * (0.4 + 0.6 * warm)
            if (isInterval) {
                val phase = (t % 240) / 240.0 * 2 * Math.PI
                hr += kotlin.math.sin(phase) * 18
            }
            hr += rnd.nextInt(7) - 3
            val bpm = hr.toLong().coerceIn(115, 178)
            hrSamples.add(HeartRateRecord.Sample(startTime.plusSeconds(t), bpm))
            t += 5
        }
        heartRate.add(HeartRateRecord(startTime, startOffset, endTime, endOffset, hrSamples, meta))

        distance.add(DistanceRecord(startTime, startOffset, endTime, endOffset, Length.kilometers(distanceKm), meta))

        val avgSpeedMs = (distanceKm * 1000.0) / durationSec
        val speedSamples = mutableListOf<SpeedRecord.Sample>()
        var st = 0L
        while (st <= durationSec) {
            val noisy = avgSpeedMs * (0.9 + rnd.nextDouble() * 0.2)
            speedSamples.add(SpeedRecord.Sample(startTime.plusSeconds(st), Velocity.metersPerSecond(noisy)))
            st += 30
        }
        speed.add(SpeedRecord(startTime, startOffset, endTime, endOffset, speedSamples, meta))

        val cadenceSamples = mutableListOf<StepsCadenceRecord.Sample>()
        var ct = 0L
        while (ct <= durationSec) {
            val rate = 168.0 + rnd.nextDouble() * 6.0 - 3.0
            cadenceSamples.add(StepsCadenceRecord.Sample(startTime.plusSeconds(ct), rate))
            ct += 30
        }
        cadence.add(StepsCadenceRecord(startTime, startOffset, endTime, endOffset, cadenceSamples, meta))

        val durationMin = durationSec / 60.0
        val activeKcal = activeKcalFor(durationMin, 11.5)
        activeCal.add(ActiveCaloriesBurnedRecord(startTime, startOffset, endTime, endOffset, Energy.kilocalories(activeKcal), meta))
        totalCal.add(TotalCaloriesBurnedRecord(startTime, startOffset, endTime, endOffset, Energy.kilocalories(activeKcal * 1.15), meta))
    }

    private fun addSimpleSession(
        rnd: Random,
        meta: Metadata,
        startTime: Instant,
        endTime: Instant,
        exerciseType: Int,
        title: String,
        hrLow: Int,
        hrHigh: Int,
        kcalPerMin: Double,
        exercises: MutableList<ExerciseSessionRecord>,
        heartRate: MutableList<HeartRateRecord>,
        activeCal: MutableList<ActiveCaloriesBurnedRecord>,
        totalCal: MutableList<TotalCaloriesBurnedRecord>,
    ) {
        val startOffset = BERLIN.rules.getOffset(LocalDateTime.ofInstant(startTime, BERLIN))
        val endOffset = BERLIN.rules.getOffset(LocalDateTime.ofInstant(endTime, BERLIN))
        val durationSec = Duration.between(startTime, endTime).seconds

        exercises.add(
            ExerciseSessionRecord(
                startTime = startTime,
                startZoneOffset = startOffset,
                endTime = endTime,
                endZoneOffset = endOffset,
                metadata = meta,
                exerciseType = exerciseType,
                title = title,
            )
        )

        val hrSamples = mutableListOf<HeartRateRecord.Sample>()
        var t = 0L
        while (t <= durationSec) {
            val bpm = (hrLow + rnd.nextInt(hrHigh - hrLow + 1)).toLong()
            hrSamples.add(HeartRateRecord.Sample(startTime.plusSeconds(t), bpm))
            t += 5
        }
        heartRate.add(HeartRateRecord(startTime, startOffset, endTime, endOffset, hrSamples, meta))

        val durationMin = durationSec / 60.0
        val activeKcal = activeKcalFor(durationMin, kcalPerMin)
        activeCal.add(ActiveCaloriesBurnedRecord(startTime, startOffset, endTime, endOffset, Energy.kilocalories(activeKcal), meta))
        totalCal.add(TotalCaloriesBurnedRecord(startTime, startOffset, endTime, endOffset, Energy.kilocalories(activeKcal * 1.15), meta))
    }

    private fun buildSleepSession(
        rnd: Random,
        meta: Metadata,
        start: Instant,
        end: Instant,
        startOffset: java.time.ZoneOffset,
        endOffset: java.time.ZoneOffset,
    ): SleepSessionRecord {
        val stages = mutableListOf<SleepSessionRecord.Stage>()
        var cursor = start
        val cyclePattern = listOf(
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_REM,
        )
        var i = 0
        while (cursor.isBefore(end)) {
            val stageType = cyclePattern[i % cyclePattern.size]
            val minutes = when (stageType) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> 15 + rnd.nextInt(20)
                SleepSessionRecord.STAGE_TYPE_REM -> 10 + rnd.nextInt(20)
                else -> 20 + rnd.nextInt(25)
            }
            var stageEnd = cursor.plusSeconds(minutes * 60L)
            if (stageEnd.isAfter(end)) stageEnd = end
            stages.add(SleepSessionRecord.Stage(cursor, stageEnd, stageType))
            cursor = stageEnd
            i++
            // Occasional brief awakenings between cycles.
            if (i % cyclePattern.size == 0 && cursor.isBefore(end) && rnd.nextInt(3) == 0) {
                val awakeEnd = minOf(cursor.plusSeconds(60L * (2 + rnd.nextInt(6))), end)
                stages.add(SleepSessionRecord.Stage(cursor, awakeEnd, SleepSessionRecord.STAGE_TYPE_AWAKE))
                cursor = awakeEnd
            }
        }
        return SleepSessionRecord(start, startOffset, end, endOffset, meta, "Schlaf", "", stages)
    }
}
