package com.myhealth.domain.engine.nutrition

import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import kotlinx.datetime.LocalDate

/**
 * Classifies one day (PLAN §3.1.6). The eight rows of the table are evaluated **in order** and the
 * first match wins; [resolve] is written as a straight-line sequence of early returns so the source
 * order is the specification order.
 *
 * Row 5 ("yesterday had a `MATCH_DAY`/`RACE_DAY`") needs the day before, so [Input.events] is
 * expected to carry the expanded occurrences for `date - 1`, `date` and `date + 1` — a superset of
 * the `date`/`date + 1` window §3.1.6 names. Occurrences for other days are ignored, so passing a
 * wider range is harmless.
 */
object DayTypeResolver {

    /** Session types that make a day a recovery day when they are the only thing planned (row 5). */
    val RECOVERY_SESSION_TYPES: Set<SessionType> = setOf(SessionType.RECOVERY_RUN, SessionType.MOBILITY)

    /** Session types that make a day hard on their own (row 6). */
    val HARD_SESSION_TYPES: Set<SessionType> = setOf(
        SessionType.INTERVAL_RUN,
        SessionType.TEMPO_RUN,
        SessionType.STRENGTH_LOWER,
        SessionType.SOCCER_TRAINING,
    )

    data class Input(
        val date: LocalDate,
        /** Expanded occurrences for `date - 1 .. date + 1` (see the class doc). */
        val events: List<EventOccurrence>,
        val plannedSessions: List<PlannedSession>,
        val completedSessions: List<ActivitySummary>,
    )

    fun resolve(input: Input): DayType {
        val today = input.date.toEpochDays()
        val tomorrow = today + 1
        val yesterday = today - 1

        // 1 — a race today.
        if (input.events.any { it.occurrenceDay == today && it.type == EventType.RACE }) {
            return DayType.RACE_DAY
        }
        // 2 — a match today.
        if (input.events.any { it.occurrenceDay == today && it.type == EventType.SOCCER_MATCH }) {
            return DayType.MATCH_DAY
        }
        // 3 — a long race tomorrow (carb loading only pays off from 10 km up).
        if (
            input.events.any {
                it.occurrenceDay == tomorrow && it.type == EventType.RACE &&
                    (it.targetDistanceMeters ?: 0.0) >= NutritionDefaults.PRE_RACE_MIN_DISTANCE_METERS
            }
        ) {
            return DayType.PRE_RACE
        }
        // 4 — a match tomorrow.
        if (input.events.any { it.occurrenceDay == tomorrow && it.type == EventType.SOCCER_MATCH }) {
            return DayType.PRE_MATCH
        }
        // 5 — the day after a match/race, or a day whose only plan is recovery work.
        val hadMatchOrRaceYesterday = input.events.any {
            it.occurrenceDay == yesterday &&
                (it.type == EventType.RACE || it.type == EventType.SOCCER_MATCH)
        }
        val onlyRecoveryPlanned = input.plannedSessions.isNotEmpty() &&
            input.plannedSessions.all { it.sessionType in RECOVERY_SESSION_TYPES }
        if (hadMatchOrRaceYesterday || onlyRecoveryPlanned) return DayType.RECOVERY

        // 6 — a hard day by load, by duration, or by session type.
        if (isHard(input)) return DayType.HARD_TRAINING

        // 7 — anything planned or done at all.
        if (input.plannedSessions.isNotEmpty() || input.completedSessions.isNotEmpty()) {
            return DayType.TRAINING
        }
        // 8 — nothing.
        return DayType.REST
    }

    private fun isHard(input: Input): Boolean {
        val trimp = input.plannedSessions.sumOf { it.estimatedTrimp ?: 0.0 } +
            input.completedSessions.sumOf { it.trimp ?: 0.0 }
        if (trimp >= NutritionDefaults.HARD_DAY_TRIMP) return true

        val durationMin = input.plannedSessions.sumOf { (it.targetDurationMin ?: 0).toDouble() } +
            input.completedSessions.sumOf { it.durationSec / 60.0 }
        if (durationMin >= NutritionDefaults.HARD_DAY_DURATION_MIN) return true

        if (input.plannedSessions.any { it.sessionType in HARD_SESSION_TYPES }) return true
        return input.completedSessions.any { it.sportType == SportType.SOCCER_TRAINING }
    }
}
