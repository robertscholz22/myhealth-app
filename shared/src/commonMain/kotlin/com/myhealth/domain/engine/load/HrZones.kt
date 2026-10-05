package com.myhealth.domain.engine.load

/**
 * Time-in-zone by 10 % heart-rate-reserve (HRR) bands (PLAN P2.9), shown as a table on the
 * Activity detail screen (charts arrive in P8).
 *
 * Five fixed bands, in order: `<60 %`, `60-70 %`, `70-80 %`, `80-90 %`, `>=90 %`. Returns minutes
 * per band in that order.
 *
 * [offsetsSec] and [hr] are the activity's raw HR stream (`ActivityStreams.sampleOffsetsSec` /
 * `.hr`, one entry per sample, same length). For each consecutive pair of samples, the interval
 * `offsetsSec[i+1] - offsetsSec[i]` is attributed to the band of `hr[i]`'s HRR — capped at 60 s so
 * a stream gap (a lost strap, a paused watch) cannot inflate one band. A `null` reading at `i`
 * (dropout) skips that whole interval rather than guessing a zone for it.
 *
 * Pure and total: never throws on malformed input (a ragged/empty stream just yields all-zero
 * minutes), matching the "engines never throw for bad input" rule (PLAN §1.5).
 */
fun timeInZones(offsetsSec: IntArray, hr: List<Int?>, hrRest: Int, hrMax: Int): List<Double> {
    val minutes = DoubleArray(HR_ZONE_COUNT)
    val range = (hrMax - hrRest).coerceAtLeast(1)
    val sampleCount = minOf(offsetsSec.size, hr.size)
    if (sampleCount < 2) return minutes.toList()

    for (i in 0 until sampleCount - 1) {
        val hrAtI = hr[i] ?: continue
        val dtSec = (offsetsSec[i + 1] - offsetsSec[i]).coerceIn(0, 60)
        if (dtSec == 0) continue
        val hrr = (hrAtI - hrRest).toDouble() / range
        minutes[bandIndex(hrr)] += dtSec / 60.0
    }
    return minutes.toList()
}

/** Number of HRR bands `timeInZones` reports (`<60,60-70,70-80,80-90,>=90`). */
const val HR_ZONE_COUNT: Int = 5

private fun bandIndex(hrr: Double): Int = when {
    hrr < 0.60 -> 0
    hrr < 0.70 -> 1
    hrr < 0.80 -> 2
    hrr < 0.90 -> 3
    else -> 4
}

/**
 * The model-driven overload (PLAN §3.9, P14.1): minutes per **named** zone Z1…Z5 of [model],
 * instead of per fixed 10 % HRR band.
 *
 * Identical accounting to the five-band [timeInZones] above — the interval to the next sample is
 * attributed to the zone of the earlier reading, capped at 60 s, and a `null` reading drops its
 * whole interval — so a [com.myhealth.domain.model.HrZoneScheme.HRR_KARVONEN] model (the default)
 * returns exactly the list the raw bands return for the same stream (test `hz06`). A `MANUAL` or
 * `LTHR_FRIEL` model is where the two legitimately differ: that is the point of an override.
 *
 * The original function is deliberately left untouched: the Activity-detail table and every
 * zone-time figure computed before P14 must keep the value they had.
 */
fun timeInZones(offsetsSec: IntArray, hr: List<Int?>, model: HrZoneModel): List<Double> {
    val minutes = DoubleArray(HR_ZONE_COUNT)
    val sampleCount = minOf(offsetsSec.size, hr.size)
    if (sampleCount < 2) return minutes.toList()

    for (i in 0 until sampleCount - 1) {
        val hrAtI = hr[i] ?: continue
        val dtSec = (offsetsSec[i + 1] - offsetsSec[i]).coerceIn(0, 60)
        if (dtSec == 0) continue
        minutes[model.zoneOf(hrAtI) - 1] += dtSec / 60.0
    }
    return minutes.toList()
}
