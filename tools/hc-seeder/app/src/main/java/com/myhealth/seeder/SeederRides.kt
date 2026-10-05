package com.myhealth.seeder

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * The two weekly rides of the seed (PLAN P12.5):
 *  - Tuesday morning: 60-min stationary trainer ride, 220 W with a 20-min 300 W block, 5-s power
 *    and pedalling-cadence samples and **no heart rate** — exercises the POWER_TSS load rung and
 *    the STREAM_20MIN FTP rung (0.95 × 300 = 285 W).
 *  - Saturday morning: 40.2 km outdoor ride in 1 h 15 min with HR, distance and speed but no
 *    power — exercises the full-ride TIME_40K best (4500 s × 40000 / 40200 ≈ 4478 s, estimated).
 */
internal class RideLists {
    val power = mutableListOf<PowerRecord>()
    val pedalCadence = mutableListOf<CyclingPedalingCadenceRecord>()
}

internal fun addTrainerRide(
    zone: ZoneId,
    rnd: Random,
    meta: Metadata,
    startTime: Instant,
    exercises: MutableList<ExerciseSessionRecord>,
    activeCal: MutableList<ActiveCaloriesBurnedRecord>,
    totalCal: MutableList<TotalCaloriesBurnedRecord>,
    rides: RideLists,
) {
    val endTime = startTime.plusSeconds(60 * 60L)
    val startOffset = zone.rules.getOffset(LocalDateTime.ofInstant(startTime, zone))
    val endOffset = zone.rules.getOffset(LocalDateTime.ofInstant(endTime, zone))
    val durationSec = Duration.between(startTime, endTime).seconds

    exercises.add(
        ExerciseSessionRecord(
            startTime = startTime,
            startZoneOffset = startOffset,
            endTime = endTime,
            endZoneOffset = endOffset,
            metadata = meta,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
            title = "Trainer",
        )
    )

    // 220 W ±3 W steady, exactly 300 W from minute 20 to minute 40 (1200 s).
    val powerSamples = mutableListOf<PowerRecord.Sample>()
    val cadenceSamples = mutableListOf<CyclingPedalingCadenceRecord.Sample>()
    var t = 0L
    while (t <= durationSec) {
        val inBlock = t >= 1200 && t < 2400
        val base = if (inBlock) 300.0 else 220.0
        // The block is held at exactly 300 W so the 20-min best (and FTP 285) is deterministic.
        val watts = if (inBlock) base else base + (rnd.nextInt(7) - 3).toDouble()
        powerSamples.add(PowerRecord.Sample(startTime.plusSeconds(t), Power.watts(watts)))
        cadenceSamples.add(
            CyclingPedalingCadenceRecord.Sample(startTime.plusSeconds(t), 88.0 + rnd.nextDouble() * 6.0 - 3.0)
        )
        t += 5
    }
    rides.power.add(PowerRecord(startTime, startOffset, endTime, endOffset, powerSamples, meta))
    rides.pedalCadence.add(
        CyclingPedalingCadenceRecord(startTime, startOffset, endTime, endOffset, cadenceSamples, meta)
    )

    // ~247 W average × 3600 s ≈ 888 kJ ≈ 888 kcal at 24 % efficiency.
    val activeKcal = 888.0
    activeCal.add(ActiveCaloriesBurnedRecord(startTime, startOffset, endTime, endOffset, Energy.kilocalories(activeKcal), meta))
    totalCal.add(TotalCaloriesBurnedRecord(startTime, startOffset, endTime, endOffset, Energy.kilocalories(activeKcal * 1.1), meta))
}

internal fun addOutdoorRide(
    zone: ZoneId,
    rnd: Random,
    meta: Metadata,
    startTime: Instant,
    exercises: MutableList<ExerciseSessionRecord>,
    heartRate: MutableList<HeartRateRecord>,
    distance: MutableList<DistanceRecord>,
    speed: MutableList<SpeedRecord>,
    activeCal: MutableList<ActiveCaloriesBurnedRecord>,
    totalCal: MutableList<TotalCaloriesBurnedRecord>,
) {
    val durationSec = 75 * 60L
    val distanceKm = 40.2
    val endTime = startTime.plusSeconds(durationSec)
    val startOffset = zone.rules.getOffset(LocalDateTime.ofInstant(startTime, zone))
    val endOffset = zone.rules.getOffset(LocalDateTime.ofInstant(endTime, zone))

    exercises.add(
        ExerciseSessionRecord(
            startTime = startTime,
            startZoneOffset = startOffset,
            endTime = endTime,
            endZoneOffset = endOffset,
            metadata = meta,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            title = "Rennrad",
        )
    )

    val hrSamples = mutableListOf<HeartRateRecord.Sample>()
    var t = 0L
    while (t <= durationSec) {
        val bpm = (135 + rnd.nextInt(21)).toLong()
        hrSamples.add(HeartRateRecord.Sample(startTime.plusSeconds(t), bpm))
        t += 5
    }
    heartRate.add(HeartRateRecord(startTime, startOffset, endTime, endOffset, hrSamples, meta))

    distance.add(DistanceRecord(startTime, startOffset, endTime, endOffset, Length.kilometers(distanceKm), meta))

    val avgSpeedMs = distanceKm * 1000.0 / durationSec
    val speedSamples = mutableListOf<SpeedRecord.Sample>()
    var st = 0L
    while (st <= durationSec) {
        val noisy = avgSpeedMs * (0.92 + rnd.nextDouble() * 0.16)
        speedSamples.add(SpeedRecord.Sample(startTime.plusSeconds(st), Velocity.metersPerSecond(noisy)))
        st += 30
    }
    speed.add(SpeedRecord(startTime, startOffset, endTime, endOffset, speedSamples, meta))

    val activeKcal = 75.0 * 10.0
    activeCal.add(ActiveCaloriesBurnedRecord(startTime, startOffset, endTime, endOffset, Energy.kilocalories(activeKcal), meta))
    totalCal.add(TotalCaloriesBurnedRecord(startTime, startOffset, endTime, endOffset, Energy.kilocalories(activeKcal * 1.15), meta))
}
