package com.myhealth.domain.engine.plan

import com.myhealth.domain.engine.calendar.EventActivityLinker
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.math.abs
import kotlin.math.floor

/** One planned session the recorded activity [activityId] completes (PLAN P6.8). */
data class PlannedCompletion(
    val sessionId: Long,
    val activityId: Long,
    val confidence: Double,
)

/**
 * Marks a planned session done when the athlete actually did it (PLAN P6.8).
 *
 * Same idea as [EventActivityLinker] (P3.3) but over `planned_session` instead of
 * `calendar_event`, and with the weights P6.8 gives, because a planned session is a *plan*, not an
 * appointment: it usually carries no clock time at all, so sport is what identifies it and the
 * start time can only ever be corroborating evidence.
 *
 * ```
 * sportScore    = 1.0 same SportType · 0.8 same SportGroup · 0.0 otherwise
 * timeScore     = 1.0 - clamp(|startDelta| / 90 min, 0, 1)   (1.0 when the session has no time)
 * durationScore = 1.0 - clamp(|plannedMin - actualMin| / plannedMin, 0, 1)  (0.5 when no target)
 * confidence    = 0.5*sportScore + 0.3*timeScore + 0.2*durationScore
 * ```
 *
 * A session is completed only at [MIN_CONFIDENCE] or above, which a wrong-sport activity can never
 * reach (`0.3 + 0.2 = 0.5 < 0.7`) — the rule that keeps a Tuesday bike ride from ticking off the
 * Tuesday long run. Confidence is rounded to six decimals before it meets the threshold, exactly
 * as in [EventActivityLinker], so a boundary case on paper is a boundary case in code.
 *
 * Pure and allocation-light: the caller ([com.myhealth.domain.repository.PlanRepository] writes)
 * decides what to do with the returned list. Each activity completes at most one session and each
 * session takes at most one activity; ties break on the lower activity id so a rerun is stable.
 */
object PlannedAutoCompleter {

    /** P6.8: below this, the match is not certain enough to tick a session off by itself. */
    const val MIN_CONFIDENCE: Double = 0.7

    const val SPORT_WEIGHT: Double = 0.5
    const val TIME_WEIGHT: Double = 0.3
    const val DURATION_WEIGHT: Double = 0.2

    /** A planned session with no target duration cannot corroborate or contradict — half a point. */
    const val NEUTRAL_DURATION_SCORE: Double = 0.5

    private const val PRECISION_SCALE = 1_000_000.0
    private const val MINUTES_PER_DAY = 1440
    private const val SECONDS_PER_MINUTE = 60.0

    /**
     * The completions to apply for one window of [sessions] and [activities].
     *
     * Only `PLANNED` sessions are considered — a session already `COMPLETED`, `SKIPPED` or `MOVED`
     * is the user's word on the matter and is never touched. An activity already linked to some
     * session is likewise off the table.
     */
    fun complete(
        sessions: List<PlannedSession>,
        activities: List<ActivitySummary>,
        zone: TimeZone,
    ): List<PlannedCompletion> {
        val taken = sessions.mapNotNull { it.linkedActivityId }.toMutableSet()
        val open = sessions
            .filter { it.status == PlannedStatus.PLANNED && it.linkedActivityId == null }
            .sortedWith(compareBy({ it.day }, { it.id }))

        val completions = mutableListOf<PlannedCompletion>()
        for (session in open) {
            val best = activities
                .asSequence()
                .filter { it.day == session.day && it.id !in taken }
                .map { activity -> activity to confidence(session, activity, zone) }
                .filter { (_, confidence) -> confidence >= MIN_CONFIDENCE }
                .sortedWith(
                    compareByDescending<Pair<ActivitySummary, Double>> { it.second }
                        .thenBy { it.first.id },
                )
                .firstOrNull() ?: continue
            taken += best.first.id
            completions += PlannedCompletion(
                sessionId = session.id,
                activityId = best.first.id,
                confidence = best.second,
            )
        }
        return completions
    }

    /** The P6.8 confidence that [activity] is [session] carried out; 0 on a different day. */
    fun confidence(session: PlannedSession, activity: ActivitySummary, zone: TimeZone): Double {
        if (activity.day != session.day) return 0.0
        val raw = SPORT_WEIGHT * sportScore(session, activity) +
            TIME_WEIGHT * timeScore(session, activity, zone) +
            DURATION_WEIGHT * durationScore(session, activity)
        return round(raw)
    }

    private fun sportScore(session: PlannedSession, activity: ActivitySummary): Double = when {
        session.sportType == activity.sportType -> 1.0
        session.sportType.group == activity.sportGroup -> 0.8
        else -> 0.0
    }

    /** Start-time agreement; a session with no planned time simply does not constrain the match. */
    private fun timeScore(session: PlannedSession, activity: ActivitySummary, zone: TimeZone): Double {
        val plannedStart = session.startMinuteOfDay ?: return 1.0
        val actualStart = minuteOfDay(activity.startAtMillis, zone)
        val delta = abs(actualStart - plannedStart).toDouble()
        return 1.0 - (delta / EventActivityLinker.START_WINDOW_MIN).coerceIn(0.0, 1.0)
    }

    /** How close the recorded duration came to the planned one, relative to the plan. */
    private fun durationScore(session: PlannedSession, activity: ActivitySummary): Double {
        val plannedMin = session.targetDurationMin?.takeIf { it > 0 } ?: return NEUTRAL_DURATION_SCORE
        val actualMin = activity.durationSec / SECONDS_PER_MINUTE
        return 1.0 - (abs(actualMin - plannedMin) / plannedMin).coerceIn(0.0, 1.0)
    }

    private fun minuteOfDay(atMillis: Long, zone: TimeZone): Int {
        val local = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
        return local.time.toSecondOfDay() / SECONDS_PER_MINUTE.toInt() % MINUTES_PER_DAY
    }

    /** Half-up rounding to six decimals (amendment A4 in §0.0). */
    private fun round(value: Double): Double =
        floor(value * PRECISION_SCALE + 0.5) / PRECISION_SCALE
}
