package com.myhealth.domain.engine.nutrition

import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.SportType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlinx.datetime.atStartOfDayIn

/**
 * MET values per sport (PLAN §3.1.2).
 *
 * Running is not a constant: the ACSM running equation gives `VO2 = 0.2 * v[m/min] + 3.5`, and
 * `MET = VO2 / 3.5`, so `MET = 0.0571 * v[m/min] + 1 = 0.952 * v[km/h] + 1`. It is clamped to
 * `[6, 20]` (a 6.3 km/h shuffle and a 20 km/h sprint) and falls back to 9.8 (≈ 9.2 km/h) when the
 * speed is unknown.
 */
object MetTable {

    const val RUN_SLOPE_PER_KMH: Double = 0.952
    const val RUN_INTERCEPT: Double = 1.0
    const val RUN_MET_MIN: Double = 6.0
    const val RUN_MET_MAX: Double = 20.0

    /** MET used for a run with no usable speed — ≈ 9.2 km/h. */
    const val RUN_MET_UNKNOWN_SPEED: Double = 9.8

    /** ACSM running MET for [speedKmh], clamped to `[RUN_MET_MIN, RUN_MET_MAX]`. */
    fun runMet(speedKmh: Double): Double =
        NutritionDefaults.clamp(RUN_SLOPE_PER_KMH * speedKmh + RUN_INTERCEPT, RUN_MET_MIN, RUN_MET_MAX)

    /**
     * MET for [sportType]; [speedKmh] is only consulted for the running types and may be `null`.
     */
    fun met(sportType: SportType, speedKmh: Double? = null): Double = when (sportType) {
        SportType.SOCCER_MATCH -> 10.0
        SportType.SOCCER_TRAINING -> 7.0
        SportType.RUN_OUTDOOR,
        SportType.RUN_TREADMILL,
        SportType.RUN_TRACK,
        SportType.RUN_TRAIL,
        -> speedKmh?.takeIf { it > 0.0 }?.let { runMet(it) } ?: RUN_MET_UNKNOWN_SPEED
        SportType.WALK -> 3.5
        SportType.HIKE -> 6.0
        SportType.CYCLING -> 8.0
        SportType.CYCLING_INDOOR -> 7.0
        SportType.STRENGTH -> 5.0
        SportType.HIIT -> 8.0
        SportType.MOBILITY -> 2.5
        SportType.SWIM -> 8.0
        SportType.ROWING -> 7.0
        SportType.OTHER, SportType.UNKNOWN -> 6.0
    }

    /**
     * `kcal = (MET - 1) * W * hours` (§3.1.2). The `- 1` removes the resting component, which the
     * BMR term of the TDEE already accounts for.
     */
    fun metKcal(met: Double, weightKg: Double, durationHours: Double): Double =
        max(0.0, (met - 1.0) * weightKg * max(0.0, durationHours))
}

/** Training energy and duration for one day, as the calorie target and the water/salt rules need it. */
data class TrainingEnergy(
    val kcal: Double,
    val durationMin: Double,
    /** True when any counted session is a strength session (drives the protein bonus, §3.1.5). */
    val hasStrength: Boolean,
) {
    val durationHours: Double get() = durationMin / 60.0

    companion object {
        val NONE = TrainingEnergy(kcal = 0.0, durationMin = 0.0, hasStrength = false)
    }
}

/**
 * Sums the day's training energy (PLAN §3.1.2) over the completed activities plus the planned
 * sessions that were *not* executed: "if a planned session and a completed session overlap in time
 * by > 50 %, count **only** the completed one (the plan was executed)".
 *
 * Overlap needs wall-clock times, and a [PlannedSession] carries only `day` +
 * `startMinuteOfDay`; the [TimeZone] the caller's days are expressed in therefore has to be passed
 * in. A planned session with no start time cannot be matched by time — it is instead treated as
 * executed when it is already linked to one of the day's completed activities, and counted
 * otherwise.
 */
object TrainingEnergyCalculator {

    /** A planned session counts as executed at more than this share of its duration. */
    const val EXECUTED_OVERLAP_SHARE: Double = 0.5

    fun forDay(
        date: LocalDate,
        completed: List<ActivitySummary>,
        planned: List<PlannedSession>,
        weightKg: Double,
        zone: TimeZone,
    ): TrainingEnergy {
        var kcal = 0.0
        var minutes = 0.0
        var hasStrength = false

        for (activity in completed) {
            kcal += completedKcal(activity, weightKg)
            minutes += activity.durationSec / 60.0
            if (activity.sportType == SportType.STRENGTH) hasStrength = true
        }
        for (session in planned) {
            if (isExecuted(date, session, completed, zone)) continue
            kcal += plannedKcal(session, weightKg)
            minutes += (session.targetDurationMin ?: 0).toDouble()
            if (session.sportType == SportType.STRENGTH) hasStrength = true
        }
        return TrainingEnergy(kcal = kcal, durationMin = minutes, hasStrength = hasStrength)
    }

    /**
     * `activeEnergyKcal` when the source recorded one, else cycling power, else the MET estimate
     * (§3.1.2, extended by P12.2).
     *
     * The power rung is `avgPowerW * durationSec / 1000`: a rider's mechanical work in kilojoules
     * is numerically almost exactly their metabolic cost in kilocalories, because gross cycling
     * efficiency sits near 24 % and 1 kcal = 4.184 kJ (`1 / 0.24 / 4.184 ≈ 1.00`). It beats the MET
     * table because it is measured work rather than a table row, and loses to a recorded
     * `activeEnergyKcal`, which is the source's own (usually HR-informed) number.
     */
    fun completedKcal(activity: ActivitySummary, weightKg: Double): Double {
        val recorded = activity.activeEnergyKcal
        if (recorded != null && recorded > 0.0) return recorded
        val power = activity.avgPowerW
        if (power != null && power > 0 && activity.durationSec > 0) {
            return power.toDouble() * activity.durationSec / 1000.0
        }
        val met = MetTable.met(activity.sportType, completedSpeedKmh(activity))
        return MetTable.metKcal(met, weightKg, activity.durationSec / 3600.0)
    }

    /** Planned sessions have no recorded energy: always the MET estimate over `targetDurationMin`. */
    fun plannedKcal(session: PlannedSession, weightKg: Double): Double {
        val minutes = session.targetDurationMin ?: return 0.0
        val met = MetTable.met(session.sportType, plannedSpeedKmh(session))
        return MetTable.metKcal(met, weightKg, minutes / 60.0)
    }

    /** Average speed in km/h from the recorded average, else from distance ÷ duration. */
    fun completedSpeedKmh(activity: ActivitySummary): Double? {
        activity.avgSpeedMps?.takeIf { it > 0.0 }?.let { return it * 3.6 }
        val distance = activity.distanceMeters ?: return null
        if (distance <= 0.0 || activity.durationSec <= 0) return null
        return distance / activity.durationSec * 3.6
    }

    /** Target speed in km/h from `targetPaceSecPerKm`, else from distance ÷ planned duration. */
    fun plannedSpeedKmh(session: PlannedSession): Double? {
        session.targetPaceSecPerKm?.takeIf { it > 0 }?.let { return 3600.0 / it }
        val distance = session.targetDistanceMeters ?: return null
        val minutes = session.targetDurationMin ?: return null
        if (distance <= 0.0 || minutes <= 0) return null
        return distance / 1000.0 / (minutes / 60.0)
    }

    private fun isExecuted(
        date: LocalDate,
        session: PlannedSession,
        completed: List<ActivitySummary>,
        zone: TimeZone,
    ): Boolean {
        if (session.linkedActivityId != null && completed.any { it.id == session.linkedActivityId }) return true
        val startMinute = session.startMinuteOfDay ?: return false
        val durationMin = session.targetDurationMin ?: return false
        if (durationMin <= 0) return false
        val start = date.atStartOfDayIn(zone).toEpochMilliseconds() + startMinute * 60_000L
        val end = start + durationMin * 60_000L
        return completed.any { overlapShare(start, end, it.startAtMillis, it.endAtMillis) > EXECUTED_OVERLAP_SHARE }
    }

    /** Share of `[aStart, aEnd)` covered by `[bStart, bEnd)`. */
    private fun overlapShare(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Double {
        val span = (aEnd - aStart).toDouble()
        if (span <= 0.0) return 0.0
        val overlap = (min(aEnd, bEnd) - max(aStart, bStart)).toDouble()
        return max(0.0, overlap) / span
    }
}
