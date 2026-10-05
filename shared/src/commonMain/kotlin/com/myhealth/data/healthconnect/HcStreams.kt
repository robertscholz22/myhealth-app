package com.myhealth.data.healthconnect

import com.myhealth.domain.engine.bike.PowerMath
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType

/**
 * The derivation half of `HcMapper` (PLAN P2.3, P12): everything that turns one [HcExercise]'s
 * raw sample lists into the summary metrics and the single shared-axis [ActivityStreams] the
 * domain stores. Split out of `HcMapper.kt` to keep both files inside rule R10.
 *
 * Pure functions over the plain DTOs of `HcDto.kt` — no Health Connect types, no Android.
 */

/** Metrics derived once from an [HcExercise] and reused by the session and the payload. */
internal data class Derived(
    val sportType: SportType,
    val durationSec: Int,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val avgCadenceSpm: Double?,
    val avgPowerW: Int?,
    val maxPowerW: Int?,
    val normalizedPowerW: Int?,
)

// ---- derived metrics ----------------------------------------------------------------------

internal fun HcExercise.derive(): Derived {
    val durationSec = ((endMillis - startMillis) / 1000L).toInt().coerceAtLeast(0)
    val sportType = ExerciseTypeMap.toSportType(exerciseType, title)
    val hr = heartRateSamples.map { it.bpm }
    val speed = speedSamples.map { it.value }
    // P12: a ride's cadence is pedalling cadence (rpm), everything else is step cadence (spm).
    // `avgCadenceSpm` carries both — the unit follows the sport group, as the model documents.
    val cadenceSource = if (sportType.group == SportGroup.CYCLE && pedalCadenceSamples.isNotEmpty()) {
        pedalCadenceSamples
    } else {
        cadenceSamples
    }
    val cadence = cadenceSource.map { it.value }
    val fallbackSpeed = distanceMeters?.takeIf { durationSec > 0 }?.div(durationSec)
    val powerOffsets = powerSamples.map { ((it.timeMillis - startMillis) / 1000L).toInt() }.toIntArray()
    val powerValues = powerSamples.map { it.value.roundHalfUp() }.toIntArray()
    return Derived(
        sportType = sportType,
        durationSec = durationSec,
        avgHr = if (hr.isEmpty()) null else hr.average().roundHalfUp(),
        maxHr = hr.maxOrNull(),
        avgSpeedMps = if (speed.isEmpty()) fallbackSpeed else speed.average(),
        maxSpeedMps = speed.maxOrNull(),
        avgCadenceSpm = if (cadence.isEmpty()) null else cadence.average(),
        avgPowerW = PowerMath.averagePower(powerOffsets, powerValues),
        maxPowerW = powerValues.maxOrNull(),
        // Null under 30 s of samples — see `PowerMath.normalizedPower`.
        normalizedPowerW = PowerMath.normalizedPower(powerOffsets, powerValues),
    )
}

/**
 * Builds the shared time axis (§2.2.2) from the heart-rate samples — the channel Health Connect
 * most reliably provides and the one the TRIMP engine needs (§3.2). Speed, cadence and power,
 * which arrive on their own clocks, are resampled onto that axis with a last-value-carried-
 * forward fill. The axis falls back to speed and then to power (P12), and the function returns
 * `null` only when the session has no samples whatsoever.
 *
 * Down-sampling: at most one sample per second, keeping the first of each second.
 */
internal fun HcExercise.toStreams(): ActivityStreams? {
    val hrAxis = heartRateSamples.map { it.timeMillis }.toOffsets(startMillis)
    // P12: `hr ?: speed ?: power` — a trainer ride written without a strap has no heart rate and
    // often no speed either, and without the power fallback it would carry no stream at all.
    val axis = when {
        hrAxis.isNotEmpty() -> hrAxis
        speedSamples.isNotEmpty() -> speedSamples.map { it.timeMillis }.toOffsets(startMillis)
        else -> powerSamples.map { it.timeMillis }.toOffsets(startMillis)
    }
    if (axis.isEmpty()) return null

    val offsets = axis.map { it.second }
    val hr: List<Int?> = if (hrAxis.isNotEmpty()) {
        axis.map { (index, _) -> heartRateSamples[index].bpm }
    } else {
        List(axis.size) { null }
    }
    val cadenceSource = if (
        ExerciseTypeMap.toSportType(exerciseType, title).group == SportGroup.CYCLE &&
        pedalCadenceSamples.isNotEmpty()
    ) {
        pedalCadenceSamples
    } else {
        cadenceSamples
    }
    return ActivityStreams(
        sampleOffsetsSec = offsets.toIntArray(),
        hr = hr,
        distanceMeters = null,
        speedMps = speedSamples.resampleOnto(offsets, startMillis),
        cadenceSpm = cadenceSource.resampleOnto(offsets, startMillis),
        altitudeM = null,
        latLngE7 = null,
        powerW = powerSamples.resampleOnto(offsets, startMillis)
            ?.map { it.roundHalfUp() }
            ?.toIntArray(),
        sampleCount = offsets.size,
        medianIntervalSec = offsets.medianInterval(),
    )
}

/**
 * Sample index → whole-second offset from [startMillis], at most one entry per second and never
 * negative. Input is assumed sorted by time (the reader sorts it).
 */
private fun List<Long>.toOffsets(startMillis: Long): List<Pair<Int, Int>> {
    val out = mutableListOf<Pair<Int, Int>>()
    var lastSecond = Int.MIN_VALUE
    forEachIndexed { index, timeMillis ->
        val second = ((timeMillis - startMillis) / 1000L).toInt()
        if (second >= 0 && second > lastSecond) {
            out += index to second
            lastSecond = second
        }
    }
    return out
}

/** Last-value-carried-forward resampling onto [offsets]; `null` when there is nothing to carry. */
private fun List<HcSample>.resampleOnto(offsets: List<Int>, startMillis: Long): DoubleArray? {
    if (isEmpty()) return null
    var cursor = 0
    var current = first().value
    return DoubleArray(offsets.size) { i ->
        val atMillis = startMillis + offsets[i] * 1000L
        while (cursor < size && this[cursor].timeMillis <= atMillis) {
            current = this[cursor].value
            cursor++
        }
        current
    }
}

/** Median gap between consecutive offsets; `0.0` for a single sample. */
private fun List<Int>.medianInterval(): Double {
    if (size < 2) return 0.0
    val gaps = (1 until size).map { (this[it] - this[it - 1]).toDouble() }.sorted()
    val middle = gaps.size / 2
    return if (gaps.size % 2 == 1) gaps[middle] else (gaps[middle - 1] + gaps[middle]) / 2.0
}

