package com.myhealth.ui.goals

import com.myhealth.domain.engine.goal.GoalProgress
import com.myhealth.domain.engine.running.CanonicalDistances
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.GoalType
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import com.myhealth.data.time.PlatformClock
import com.myhealth.ui.common.mathRound
import kotlinx.datetime.LocalDate

/** Field identity for [validateGoal] errors (mirrors `EventDraft`'s pattern, P3.4). */
enum class GoalField { TITLE, DISTANCE, TIME, DATE, WEIGHT, VALUE }

/**
 * The four distances the `BIKE_EVENT` editor offers (PLAN "UI.", P12.4) — the same set
 * [com.myhealth.domain.model.RideBestKind]'s `TIME_10K/20K/40K/100K` kinds measure, so a
 * `BIKE_EVENT` goal with a target time can always be matched against a `ride_best` row.
 */
val BIKE_EVENT_DISTANCES: List<Double> = listOf(10_000.0, 20_000.0, 40_000.0, 100_000.0)

/** The distance a fresh (or mismatched) `BIKE_EVENT` draft defaults to. */
const val BIKE_EVENT_DEFAULT_DISTANCE_METERS: Double = 40_000.0

/**
 * The goal editor's form state (PLAN §4.2 "Goal edit", P6.1). Which fields matter depends on
 * [type]: a race goal needs a distance + time (+ optional date and linked `RACE` event), a weight
 * goal a target weight, a consistency goal a sessions/week number.
 */
data class GoalDraft(
    val id: Long = 0L,
    val type: GoalType = GoalType.RACE_TIME,
    val title: String = "",
    val targetDay: LocalDate? = null,
    /**
     * Pre-set to the 5 km the distance picker displays for a new race goal (BUG-8): the editor
     * showed "5 km" while the draft carried `null`, so "Create goal" failed validation silently
     * until the picker was re-selected. [withType] keeps the two in step on a type change.
     */
    val targetDistanceMeters: Double? = CanonicalDistances.FIVE_KM,
    val targetMinutes: Int? = null,
    val targetSeconds: Int? = null,
    val targetWeightKg: Double? = null,
    val targetValue: Double? = null,
    val isPrimary: Boolean = false,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val linkedEventId: Long? = null,
    val notes: String = "",
    val createdAtMillis: Long = 0L,
    /** P19: a race on [targetDay] (default) or a deadline — only race-time and bike-event goals ask. */
    val isRace: Boolean = true,
) {
    /** P19: whether the editor shows the race-day/deadline switch (a race-type goal with a date). */
    val asksRaceOrDeadline: Boolean
        get() = (type == GoalType.RACE_TIME || type == GoalType.BIKE_EVENT) && targetDay != null

    /** The two time fields as one value; `null` when neither was entered. */
    val targetTimeSec: Int?
        get() = if (targetMinutes == null && targetSeconds == null) {
            null
        } else {
            (targetMinutes ?: 0) * 60 + (targetSeconds ?: 0)
        }
}

/**
 * Switches the draft's [GoalDraft.type], restoring the distance picker's default when the new type
 * is `RACE_TIME` and the draft carries no distance (BUG-8).
 */
fun GoalDraft.withType(newType: GoalType): GoalDraft = copy(
    type = newType,
    targetDistanceMeters = when (newType) {
        GoalType.RACE_TIME -> targetDistanceMeters ?: CanonicalDistances.FIVE_KM
        GoalType.BIKE_EVENT ->
            targetDistanceMeters?.takeIf { it in BIKE_EVENT_DISTANCES } ?: BIKE_EVENT_DEFAULT_DISTANCE_METERS
        else -> targetDistanceMeters
    },
)

/** Blocking errors only; an empty map means the draft can be saved. */
fun validateGoal(draft: GoalDraft): Map<GoalField, UiMessage> {
    val errors = mutableMapOf<GoalField, UiMessage>()
    if (draft.title.isBlank()) errors[GoalField.TITLE] = UiMessage.of(Res.string.goal_error_title_required)
    when (draft.type) {
        GoalType.RACE_TIME -> {
            if ((draft.targetDistanceMeters ?: 0.0) <= 0.0) {
                errors[GoalField.DISTANCE] = UiMessage.of(Res.string.goal_error_distance_required)
            }
            val seconds = draft.targetTimeSec
            if (seconds == null || seconds <= 0) errors[GoalField.TIME] = UiMessage.of(Res.string.goal_error_time_required)
            if ((draft.targetSeconds ?: 0) !in 0..59) {
                errors[GoalField.TIME] = UiMessage.of(Res.string.goal_error_seconds_range)
            }
        }
        GoalType.BODY_WEIGHT ->
            if ((draft.targetWeightKg ?: 0.0) <= 0.0) errors[GoalField.WEIGHT] = UiMessage.of(Res.string.goal_error_weight_required)
        GoalType.CONSISTENCY ->
            if ((draft.targetValue ?: 0.0) <= 0.0) errors[GoalField.VALUE] = UiMessage.of(Res.string.goal_error_sessions_required)
        GoalType.STRENGTH_LIFT, GoalType.SOCCER_AVAILABILITY, GoalType.BIKE_FTP, GoalType.BIKE_VOLUME ->
            if ((draft.targetValue ?: 0.0) <= 0.0) errors[GoalField.VALUE] = UiMessage.of(Res.string.goal_error_value_required)
        // A distance is required; the time is optional (a date-only event is tracked manually,
        // GoalProgress.bikeEvent, P12.2), so it never blocks the save.
        GoalType.BIKE_EVENT ->
            if ((draft.targetDistanceMeters ?: 0.0) <= 0.0) errors[GoalField.DISTANCE] = UiMessage.of(Res.string.goal_error_distance_required)
    }
    return errors
}

/** The domain goal the editor saves; only the fields its [GoalDraft.type] uses are carried over. */
fun GoalDraft.toGoal(clock: PlatformClock): Goal {
    val now = clock.millis()
    val isRunRace = type == GoalType.RACE_TIME
    val isBikeEvent = type == GoalType.BIKE_EVENT
    return Goal(
        id = id,
        type = type,
        title = title.trim(),
        targetDay = targetDay?.toEpochDays(),
        targetDistanceMeters = targetDistanceMeters.takeIf { isRunRace || isBikeEvent },
        targetTimeSec = targetTimeSec.takeIf { isRunRace || isBikeEvent },
        targetWeightKg = targetWeightKg.takeIf { type == GoalType.BODY_WEIGHT },
        targetValue = targetValue.takeIf { type != GoalType.RACE_TIME && type != GoalType.BODY_WEIGHT && !isBikeEvent },
        priority = if (isPrimary) 1 else 2,
        status = status,
        linkedEventId = linkedEventId.takeIf { isRunRace },
        notes = notes.trim().takeIf { it.isNotEmpty() },
        createdAtMillis = if (id == 0L) now else createdAtMillis,
        updatedAtMillis = now,
        // Only a dated race-type goal can be a deadline; everything else keeps the default.
        isRace = isRace || !asksRaceOrDeadline,
    )
}

/** Loads an existing goal into a draft. */
fun goalDraftOf(goal: Goal): GoalDraft = GoalDraft(
    id = goal.id,
    type = goal.type,
    title = goal.title,
    targetDay = goal.targetDay?.let { LocalDate.fromEpochDays(it) },
    targetDistanceMeters = goal.targetDistanceMeters,
    targetMinutes = goal.targetTimeSec?.let { it / 60 },
    targetSeconds = goal.targetTimeSec?.let { it % 60 },
    targetWeightKg = goal.targetWeightKg,
    targetValue = goal.targetValue,
    isPrimary = goal.priority <= 1,
    status = goal.status,
    linkedEventId = goal.linkedEventId,
    notes = goal.notes.orEmpty(),
    createdAtMillis = goal.createdAtMillis,
    isRace = goal.isRace,
)

/** "Race time", "Body weight", … — the §2.1 enum rendered for a dropdown. */
fun goalTypeLabel(type: GoalType): String = when (type) {
    GoalType.RACE_TIME -> "Race time"
    GoalType.BODY_WEIGHT -> "Body weight"
    GoalType.STRENGTH_LIFT -> "Strength lift"
    GoalType.CONSISTENCY -> "Consistency"
    GoalType.SOCCER_AVAILABILITY -> "Soccer availability"
    GoalType.BIKE_FTP -> "Bike FTP"
    GoalType.BIKE_VOLUME -> "Bike volume"
    GoalType.BIKE_EVENT -> "Bike event"
}

fun goalStatusLabel(status: GoalStatus): String = when (status) {
    GoalStatus.ACTIVE -> "Active"
    GoalStatus.ACHIEVED -> "Achieved"
    GoalStatus.ABANDONED -> "Abandoned"
    GoalStatus.EXPIRED -> "Expired"
}

/**
 * The one-line summary the Goals list shows under the title, e.g. `5 km in 20:00 by 2026-11-15`
 * (§4.2 "Goals": "5k 20:00 by 15 Nov — current best 21:14, on track/behind").
 */
fun goalHeadline(goal: Goal): String {
    // P19: a race happens *on* its date; a deadline goal is reached *by* it.
    val raceType = goal.type == GoalType.RACE_TIME || goal.type == GoalType.BIKE_EVENT
    val preposition = if (raceType && goal.isRace) "on" else "by"
    val by = goal.targetDay?.let { " $preposition ${LocalDate.fromEpochDays(it)}" } ?: ""
    return when (goal.type) {
        GoalType.RACE_TIME -> {
            val distance = goal.targetDistanceMeters?.let { GoalProgress.distanceLabel(it) } ?: "race"
            val time = goal.targetTimeSec?.let { " in ${GoalProgress.formatTime(it)}" } ?: ""
            "$distance$time$by"
        }
        GoalType.BODY_WEIGHT -> "${goal.targetWeightKg ?: 0.0} kg$by"
        GoalType.CONSISTENCY -> "${wholeNumber(goal.targetValue)} sessions/week$by"
        GoalType.BIKE_FTP -> "${wholeNumber(goal.targetValue)} W FTP$by"
        GoalType.BIKE_VOLUME -> "${goal.targetValue ?: 0.0} h/week riding$by"
        GoalType.BIKE_EVENT -> {
            val distance = goal.targetDistanceMeters?.let { GoalProgress.distanceLabel(it) } ?: "ride"
            val time = goal.targetTimeSec?.let { " in ${GoalProgress.formatTime(it)}" } ?: ""
            "$distance$time$by"
        }
        GoalType.STRENGTH_LIFT, GoalType.SOCCER_AVAILABILITY ->
            "${goalTypeLabel(goal.type)}: ${goal.targetValue ?: 0.0}$by"
    }
}

/** Watts and session counts are whole numbers; `300.0 W` reads wrong. */
private fun wholeNumber(value: Double?): String = mathRound(value ?: 0.0).toString()
