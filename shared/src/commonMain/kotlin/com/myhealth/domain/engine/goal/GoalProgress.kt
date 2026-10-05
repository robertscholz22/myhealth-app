package com.myhealth.domain.engine.goal

import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.bike.FtpSource
import com.myhealth.domain.engine.running.RiegelPredictor
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.model.SportGroup
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.myhealth.domain.util.epochDayDate
import com.myhealth.domain.util.dayMonthLabel
import com.myhealth.domain.util.monthYearLabel
import com.myhealth.domain.util.NumberFormat
import com.myhealth.domain.util.pad2

/**
 * Goal progress (PLAN P6.1): `compute(goal, bests, weights, today) -> Progress`.
 *
 * - `RACE_TIME` (P19.1) — current form = the faster of the best effort at the goal distance and
 *   the Riegel prediction (§3.4), both from the **last 180 days**; `percent = clamp(target /
 *   current, 0, 1)`, `onTrack` when current is within 2 % of the target. Nothing recent ⇒
 *   [Progress.isUnknown], with the all-time best only as a dated note.
 * - `BODY_WEIGHT` — linear from the weight at creation to `targetWeightKg`; `onTrack` when the
 *   achieved rate is at least 80 % of the rate needed to hit `targetDay`.
 * - `CONSISTENCY` — sessions/week over the last 4 weeks against `targetValue`.
 * - `BIKE_FTP` — `percent = clamp(currentFtp / targetWatts, 0, 1)`; `onTrack` from 95 % of the
 *   target (P12.2). The FTP itself comes from `FtpEstimator`, which the caller resolves.
 * - `BIKE_VOLUME` — riding hours over the last 4 weeks ÷ 4 against `targetValue` (hours/week).
 * - `BIKE_EVENT` — with a target time, the best `ride_best` `TIME_*` row of that distance against
 *   it; with only a date, **manual** (there is nothing to measure before the event).
 * - `STRENGTH_LIFT` / `SOCCER_AVAILABILITY` — no measurable series exists in the data model, so
 *   these are **manual**: `percent = 0`, `isManual = true`, and the text names the target. They are
 *   completed by the owner pressing "mark achieved" on the Goals screen.
 *
 * Ambiguity notes:
 * - `goal` has no `startValue` column (§2.2.4), so "the weight when the goal was created" is the
 *   first measurement **at or after** `createdAtMillis`; if the goal predates every measurement,
 *   the earliest measurement is used. No schema change is needed for this (no migration).
 * - `RiegelPredictor.pickSource` needs an effort of at least 3 km, so a 1 km goal has no
 *   prediction and reports `onTrack = false` until the goal is actually met.
 * - A goal whose target is already reached reports `percent = 1.0` and `onTrack = true`.
 */
object GoalProgress {

    /**
     * P19.1: the recent-form window of a race goal — both the "recent best" at the goal distance
     * and the Riegel source effort must lie inside it (the same 180 days the VDOT uses). It
     * replaced P6.1's 60-day prediction window and the all-time "current best".
     */
    const val RECENT_FORM_DAYS: Long = RiegelPredictor.MAX_SOURCE_AGE_DAYS

    /** A prediction within 2 % of the target still counts as on track (P6.1). */
    const val RACE_ON_TRACK_TOLERANCE: Double = 1.02

    /** The consistency goal averages over four weeks. */
    const val CONSISTENCY_WEEKS: Int = 4

    /** The bike-volume goal averages over the same four weeks (P12.2). */
    const val VOLUME_WEEKS: Int = 4

    /** A `BIKE_FTP` goal counts as on track from 95 % of the target watts (P12.2). */
    const val FTP_ON_TRACK_FRACTION: Double = 0.95

    /** "At least 80 % of the required rate" (P6.1's body-weight rule). */
    const val WEIGHT_ON_TRACK_FRACTION: Double = 0.8

    private const val DAYS_PER_WEEK = 7.0

    /** One goal's progress: [percent] in `[0,1]`, a one-line [statusText], and [onTrack]. */
    data class Progress(
        val percent: Double,
        val statusText: String,
        val onTrack: Boolean,
        /** True when nothing in the data model can measure this goal (see the class KDoc). */
        val isManual: Boolean = false,
        /** P19.1: measurable, but nothing recent to measure it with — neither behind nor on track. */
        val isUnknown: Boolean = false,
    )

    fun compute(
        goal: Goal,
        bests: List<RunningBest>,
        weights: List<BodyMeasurement>,
        today: LocalDate,
        activities: List<ActivitySummary> = emptyList(),
        /** `ride_best` rows, as the PR table (P12.2) — only the `TIME_*` kinds are read here. */
        rideBests: List<RideBest> = emptyList(),
        /** The current FTP estimate (P12.2); `null` means none could be derived yet. */
        ftp: FtpEstimate? = null,
    ): Progress = when (goal.type) {
        GoalType.RACE_TIME -> raceTime(goal, bests, today)
        GoalType.BODY_WEIGHT -> bodyWeight(goal, weights, today)
        GoalType.CONSISTENCY -> consistency(goal, activities, today)
        GoalType.STRENGTH_LIFT, GoalType.SOCCER_AVAILABILITY -> manual(goal)
        GoalType.BIKE_FTP -> bikeFtp(goal, ftp)
        GoalType.BIKE_VOLUME -> bikeVolume(goal, activities, today)
        GoalType.BIKE_EVENT -> bikeEvent(goal, rideBests)
    }

    // ---- BIKE_FTP / BIKE_VOLUME / BIKE_EVENT (P12.2) --------------------------------------------

    private fun bikeFtp(goal: Goal, ftp: FtpEstimate?): Progress {
        val target = goal.targetValue
        if (target == null || target <= 0.0) {
            return Progress(0.0, "Set a target FTP in watts.", onTrack = false, isManual = true)
        }
        if (ftp == null) {
            return Progress(
                percent = 0.0,
                statusText = "No FTP estimate yet — ride with a power meter or set the override.",
                onTrack = false,
            )
        }
        val percent = clamp01(ftp.watts / target)
        val onTrack = percent >= FTP_ON_TRACK_FRACTION
        return Progress(
            percent = percent,
            statusText = "${ftp.watts} W ${ftpSourceLabel(ftp)} · target ${fmtShort(target)} W — " +
                (if (onTrack) "on track" else "behind") + ".",
            onTrack = onTrack,
        )
    }

    private fun bikeVolume(goal: Goal, activities: List<ActivitySummary>, today: LocalDate): Progress {
        val target = goal.targetValue
        if (target == null || target <= 0.0) {
            return Progress(0.0, "Set a target of riding hours per week.", onTrack = false, isManual = true)
        }
        val todayDay = today.toEpochDays()
        val from = todayDay - VOLUME_WEEKS * DAYS_PER_WEEK.toLong() + 1
        val hours = activities
            .filter { it.sportGroup == SportGroup.CYCLE && it.day in from..todayDay }
            .sumOf { it.durationSec / 3600.0 }
        val perWeek = hours / VOLUME_WEEKS
        val onTrack = perWeek >= target
        return Progress(
            percent = clamp01(perWeek / target),
            statusText = "${fmt1(perWeek)} h/week over the last $VOLUME_WEEKS weeks " +
                "(target ${fmt1(target)} h) — " + (if (onTrack) "on track" else "behind") + ".",
            onTrack = onTrack,
        )
    }

    /**
     * A bike event with a target time is measured against the best `TIME_*` effort of that
     * distance; one with only a date is manual. There is no Riegel-style predictor for rides (the
     * exponent is a running result and a ride's time depends on the course), so "on track" here
     * means the target time has actually been ridden, not that it is projected to be.
     */
    private fun bikeEvent(goal: Goal, rideBests: List<RideBest>): Progress {
        val targetSec = goal.targetTimeSec
        val distance = goal.targetDistanceMeters
        if (targetSec == null || targetSec <= 0 || distance == null || distance <= 0.0) {
            return Progress(
                percent = 0.0,
                statusText = "Tracked manually — mark it achieved after the event.",
                onTrack = true,
                isManual = true,
            )
        }
        val current = rideBests
            .filter { !it.kind.isPower && matches(it.kind, distance) && it.value > 0.0 }
            .minByOrNull { it.value }
            ?: return Progress(
                percent = 0.0,
                statusText = "No ${distanceLabel(distance)} ride recorded yet.",
                onTrack = false,
            )
        val currentSec = current.value
        val percent = clamp01(targetSec / currentSec)
        val onTrack = percent >= 1.0
        val estimated = if (current.isEstimated) " (estimated)" else ""
        return Progress(
            percent = percent,
            statusText = "Best ${formatTime(currentSec.toInt())}$estimated · " +
                "target ${formatTime(targetSec)} — " + (if (onTrack) "on track" else "behind") + ".",
            onTrack = onTrack,
        )
    }

    private fun matches(kind: RideBestKind, distanceMeters: Double): Boolean =
        kind.distanceMeters?.let { abs(it - distanceMeters) < 1.0 } ?: false

    private fun ftpSourceLabel(ftp: FtpEstimate): String = when (ftp.source) {
        FtpSource.MANUAL -> "(set manually)"
        FtpSource.STREAM_20MIN -> "(from a 20-minute best)"
        FtpSource.SESSION_NP -> "(estimated from a ride's average power)"
    }

    // ---- RACE_TIME -----------------------------------------------------------------------------

    private fun raceTime(goal: Goal, bests: List<RunningBest>, today: LocalDate): Progress {
        val targetSec = goal.targetTimeSec
        val distance = goal.targetDistanceMeters
        if (targetSec == null || targetSec <= 0 || distance == null || distance <= 0.0) {
            return Progress(0.0, "Set a target distance and time.", onTrack = false, isManual = true)
        }
        val todayDay = today.toEpochDays()
        val fromDay = todayDay - RECENT_FORM_DAYS
        val atDistance = bests.filter { abs(it.distanceMeters - distance) < 1.0 && it.timeSec > 0 }
        val allTime = atDistance.minByOrNull { it.timeSec }
        val recentBest = atDistance.filter { it.day >= fromDay }.minByOrNull { it.timeSec }
        val recent = bests.filter { it.day >= fromDay }
        val source = RiegelPredictor.pickSource(recent, todayDay)
        val predicted = predictedSec(recent, distance, todayDay)
        val current = listOfNotNull(recentBest?.timeSec?.toDouble(), predicted).minOrNull()
            ?: return unknownForm(distance, allTime)

        val percent = clamp01(targetSec / current)
        val onTrack = current <= targetSec * RACE_ON_TRACK_TOLERANCE
        val verdict = if (onTrack) "on track" else "behind"
        val text = if (recentBest != null) {
            val predictionNote = predicted
                ?.takeIf { it < recentBest.timeSec - 0.5 }
                ?.let { " · predicted ${formatTime(it.toInt())}" }
                .orEmpty()
            "Recent best ${formatTime(recentBest.timeSec)} (${dayLabel(recentBest.day)})$predictionNote — $verdict."
        } else {
            val from = source?.let { " from your ${distanceLabel(it.distanceMeters)} on ${dayLabel(it.day)}" }.orEmpty()
            "Predicted ${formatTime(current.toInt())}$from — $verdict."
        }
        return Progress(percent = percent, statusText = text, onTrack = onTrack)
    }

    /** P19.1: nothing inside the recent-form window — say so instead of comparing to an old PR. */
    private fun unknownForm(distance: Double, allTime: RunningBest?): Progress {
        val note = allTime?.let { " (all-time ${formatTime(it.timeSec)}, ${monthLabel(it.day)})" } ?: ""
        return Progress(
            percent = 0.0,
            statusText = "No ${distanceLabel(distance)} effort in the last 6 months$note — " +
                "run a time trial to measure your form.",
            onTrack = false,
            isUnknown = true,
        )
    }

    private fun dayLabel(day: Long): String =
        day.epochDayDate().dayMonthLabel()

    private fun monthLabel(day: Long): String =
        day.epochDayDate().monthYearLabel()

    /** Riegel from the best qualifying effort of the last [RECENT_FORM_DAYS] days. */
    fun predictedSec(bests: List<RunningBest>, targetDistanceMeters: Double, todayDay: Long): Double? {
        val recent = bests.filter { it.day >= todayDay - RECENT_FORM_DAYS }
        val source = RiegelPredictor.pickSource(recent, todayDay) ?: return null
        return RiegelPredictor.predictSec(
            sourceDistanceMeters = source.distanceMeters,
            sourceTimeSec = source.timeSec.toDouble(),
            targetDistanceMeters = targetDistanceMeters,
        )
    }

    // ---- BODY_WEIGHT ---------------------------------------------------------------------------

    private fun bodyWeight(goal: Goal, weights: List<BodyMeasurement>, today: LocalDate): Progress {
        val target = goal.targetWeightKg
            ?: return Progress(0.0, "Set a target weight.", onTrack = false, isManual = true)
        val series = weights.filter { it.weightKg != null }.sortedBy { it.day }
        val start = startMeasurement(series, goal.createdAtMillis)
        val current = series.lastOrNull()
        if (start?.weightKg == null || current?.weightKg == null) {
            return Progress(0.0, "Log a weight to start tracking this goal.", onTrack = false)
        }
        val startKg = start.weightKg
        val currentKg = current.weightKg
        val total = startKg - target
        val achieved = startKg - currentKg
        val percent = if (abs(total) < 1e-6) 1.0 else clamp01(achieved / total)

        val todayDay = today.toEpochDays()
        val onTrack = percent >= 1.0 || isWeightOnTrack(goal, start.day, todayDay, total, achieved)
        val delta = currentKg - target
        return Progress(
            percent = percent,
            statusText = "${fmt1(currentKg)} kg · ${fmt1(abs(delta))} kg " +
                (if (delta > 0) "to go" else "past target") + " — " +
                (if (onTrack) "on track" else "behind") + ".",
            onTrack = onTrack,
        )
    }

    /** The first measurement at or after the goal's creation, falling back to the earliest one. */
    fun startMeasurement(series: List<BodyMeasurement>, createdAtMillis: Long): BodyMeasurement? =
        series.firstOrNull { it.measuredAtMillis >= createdAtMillis } ?: series.firstOrNull()

    private fun isWeightOnTrack(
        goal: Goal,
        startDay: Long,
        todayDay: Long,
        total: Double,
        achieved: Double,
    ): Boolean {
        val targetDay = goal.targetDay ?: return achieved * sign(total) > 0.0
        val weeksTotal = max((targetDay - startDay) / DAYS_PER_WEEK, 1.0 / DAYS_PER_WEEK)
        val weeksElapsed = max((todayDay - startDay) / DAYS_PER_WEEK, 1.0 / DAYS_PER_WEEK)
        val required = total / weeksTotal
        val actual = achieved / weeksElapsed
        val s = sign(total)
        return actual * s >= WEIGHT_ON_TRACK_FRACTION * required * s
    }

    // ---- CONSISTENCY ---------------------------------------------------------------------------

    private fun consistency(goal: Goal, activities: List<ActivitySummary>, today: LocalDate): Progress {
        val target = goal.targetValue
        if (target == null || target <= 0.0) {
            return Progress(0.0, "Set a sessions-per-week target.", onTrack = false, isManual = true)
        }
        val todayDay = today.toEpochDays()
        val from = todayDay - CONSISTENCY_WEEKS * DAYS_PER_WEEK.toLong() + 1
        val sessions = activities.count { it.day in from..todayDay }
        val perWeek = sessions / CONSISTENCY_WEEKS.toDouble()
        val onTrack = perWeek >= target
        return Progress(
            percent = clamp01(perWeek / target),
            statusText = "${fmt1(perWeek)} sessions/week over the last $CONSISTENCY_WEEKS weeks " +
                "(target ${fmt1(target)}) — " + (if (onTrack) "on track" else "behind") + ".",
            onTrack = onTrack,
        )
    }

    // ---- manual goals ---------------------------------------------------------------------------

    private fun manual(goal: Goal): Progress {
        val target = goal.targetValue?.let { " (target ${fmt1(it)})" } ?: ""
        return Progress(
            percent = 0.0,
            statusText = "Tracked manually$target — mark it achieved when you get there.",
            onTrack = true,
            isManual = true,
        )
    }

    // ---- formatting -----------------------------------------------------------------------------

    /** `mm:ss`, or `h:mm:ss` from an hour up. */
    fun formatTime(seconds: Int): String {
        val s = max(0, seconds)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val secs = s % 60
        return if (hours > 0) {
            "$hours:${minutes.pad2()}:${secs.pad2()}"
        } else {
            "$minutes:${secs.pad2()}"
        }
    }

    fun distanceLabel(meters: Double): String = when {
        abs(meters - 21097.5) < 1.0 -> "half marathon"
        abs(meters - 42195.0) < 1.0 -> "marathon"
        abs(meters - 1609.34) < 1.0 -> "mile"
        meters >= 1000.0 -> "${fmtShort(meters / 1000.0)} km"
        else -> "${meters.toInt()} m"
    }

    private fun fmt1(value: Double): String = NumberFormat.fixed(value, 1)

    private fun fmtShort(value: Double): String =
        if (abs(value - value.toInt()) < 1e-6) value.toInt().toString() else fmt1(value)

    private fun sign(value: Double): Double = if (value >= 0.0) 1.0 else -1.0

    private fun clamp01(value: Double): Double = max(0.0, min(value, 1.0))
}
