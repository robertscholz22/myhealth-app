package com.myhealth.ui.goals

import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.goal.GoalProgress
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RunningBest
import com.myhealth.ui.common.UiMessage
import kotlinx.datetime.LocalDate

/** One row of the Goals list: the goal plus its computed progress (PLAN §4.2 "Goals", P6.1). */
data class GoalRow(
    val goal: Goal,
    val progress: GoalProgress.Progress,
) {
    val isPrimary: Boolean get() = goal.priority <= 1 && goal.status == GoalStatus.ACTIVE
}

/** ViewModel state for [GoalsScreen]. */
data class GoalsUiState(
    val isLoading: Boolean = true,
    val active: List<GoalRow> = emptyList(),
    val archived: List<GoalRow> = emptyList(),
    val message: UiMessage? = null,
    /**
     * True when an active `BIKE_*` goal exists but the `CYCLE` preferred-sports cap is 0 (P12.3's
     * onboarding default) — C10 then blocks every ride, so the goal alone produces no suggestions.
     */
    val showCycleCapHint: Boolean = false,
) {
    val isEmpty: Boolean get() = !isLoading && active.isEmpty() && archived.isEmpty()
}

/** The three cycling goal types (§2.1 `GoalType`, P12) — used to gate [GoalsUiState.showCycleCapHint]. */
val BIKE_GOAL_TYPES: Set<GoalType> = setOf(GoalType.BIKE_FTP, GoalType.BIKE_VOLUME, GoalType.BIKE_EVENT)

/**
 * Pure projection of the repositories onto [GoalRow]s — kept out of the ViewModel so it can be
 * unit-tested without coroutines. Active goals come first, primary first, then by target day.
 */
fun goalRows(
    goals: List<Goal>,
    bests: List<RunningBest>,
    weights: List<BodyMeasurement>,
    activities: List<ActivitySummary>,
    today: LocalDate,
    /** `ride_best` PR rows and the current FTP estimate — the cycling goals' inputs (P12.2). */
    rideBests: List<RideBest> = emptyList(),
    ftp: FtpEstimate? = null,
): List<GoalRow> = goals.map { goal ->
    GoalRow(
        goal = goal,
        progress = GoalProgress.compute(goal, bests, weights, today, activities, rideBests, ftp),
    )
}

/** §4.2's list order: primary goal first, then the nearest deadline, then the newest goal. */
fun List<GoalRow>.sortedForDisplay(): List<GoalRow> = sortedWith(
    compareBy<GoalRow> { it.goal.priority }
        .thenBy { it.goal.targetDay ?: Long.MAX_VALUE }
        .thenByDescending { it.goal.createdAtMillis },
)

fun List<GoalRow>.activeOnly(): List<GoalRow> = filter { it.goal.status == GoalStatus.ACTIVE }

fun List<GoalRow>.archivedOnly(): List<GoalRow> = filter { it.goal.status != GoalStatus.ACTIVE }
