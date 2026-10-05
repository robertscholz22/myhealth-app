package com.myhealth.domain.engine.suggest

import com.myhealth.domain.engine.running.PaceZoneBand
import com.myhealth.domain.engine.strength.MuscleLoadState
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.RecoveryState
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutKind
import kotlinx.datetime.LocalDate

/**
 * Everything the training-suggestion engine reads (PLAN §3.5.1). Nothing else: the engine is a
 * pure function of this object, which is what makes [SuggestionEngine]'s `inputsHash` meaningful.
 *
 * Two documented extensions of the §3.5.1 field list:
 * - [planStartDay] — the active plan's start. Until 0.9.0 the `RECOVERY_WEEK` override counted
 *   weeks from it; since P19.6 the down week comes from [recentLoad] and nothing reads this field.
 *   It stays in the input (and in the inputs hash) so stored hashes remain comparable.
 * - [horizonDays] keeps its §3.5.1 default of 7; the settings key `suggestionHorizonDays` feeds it.
 * - [cycleStatusByDay] — added by P11.2; empty unless the user tracks their cycle.
 * - [vdot] / [paceBands] / [ftpWatts] — added by P14.3 for `IntervalBuilder`. All three default to
 *   "unknown", and the engine's candidate generation, scoring and constraints never read them: they
 *   only decide how much of a *structure* a placed session can carry, which is what keeps every
 *   pre-P14 output byte-identical (`sug28`).
 * - [muscleLoad] / [lastAcceptedTemplateByKind] — added by P14.5 for `StrengthRules` and `C15`
 *   (§3.12.5). `null` muscle load switches the whole strength layer off, which is what `sug39`
 *   and `sug40` pin.
 */
data class SuggestionInput(
    val today: LocalDate,
    val horizonDays: Int = 7,
    val profile: Profile,
    /** `ACTIVE` goals, sorted by `priority` ascending (1 = primary). */
    val goals: List<Goal> = emptyList(),
    /** Expanded occurrences covering the horizon **+ 3 days** (C1/C2 look ahead). */
    val events: List<EventOccurrence> = emptyList(),
    /**
     * The planned sessions the suggester must treat as fixed: `locked == true` ones and (BUG-15)
     * sessions the user planned by hand (`sourceSuggestionId == null`). It may not move or overlap
     * them (C6) and their load counts against the weekly target.
     */
    val lockedPlanned: List<PlannedSession> = emptyList(),
    /** The last 42 days of `daily_load`, ascending by day. */
    val recentLoad: List<DailyLoad> = emptyList(),
    val recovery: RecoveryState? = null,
    /** The last 14 days of activities — C4's "TRIMP ≥ 200 yesterday" rule and the sport caps. */
    val recentActivities: List<ActivitySummary> = emptyList(),
    val planStartDay: Long? = null,
    /**
     * P11.2: where each horizon day sits in the menstrual cycle. Empty whenever cycle tracking is
     * off, which is exactly what makes the four `CYCLE_*` rules inert for everybody else.
     */
    val cycleStatusByDay: Map<Long, CycleStatus> = emptyMap(),
    /** P14.3: the athlete's VDOT (§3.4), the Daniels anchor of every pace target. */
    val vdot: Double? = null,
    /** P14.3: the measured pace band per heart-rate zone (§3.10.2), Z1…Z5. */
    val paceBands: List<PaceZoneBand> = emptyList(),
    /** P14.3: the FTP every bike power target is a percentage of (§3.8.1). */
    val ftpWatts: Int? = null,
    /**
     * P14.5: today's per-muscle-group load (§3.12.4), or `null` when nothing computed it. `null`
     * makes `C15`, `Scorer.muscleBonus`, the three muscle rationale ids and the proposed workout
     * template all inert — the gating invariant of §3.12.5.
     */
    val muscleLoad: MuscleLoadState? = null,
    /**
     * P14.5: the most recently accepted built-in workout per kind (`UPPER` → `"UPPER_A"`, …), which
     * is what makes the proposed template alternate (§3.12.5). Only read while [muscleLoad] is set.
     */
    val lastAcceptedTemplateByKind: Map<StrengthWorkoutKind, String> = emptyMap(),
    /**
     * P19: the goal facts of [GoalRules] (benchmark, long run, goal pace). `null` switches that
     * whole layer off — every pre-P19 fixture leaves it `null`.
     */
    val goalForm: GoalFormInputs? = null,
    /**
     * P19: the workouts checked "Use in suggestions", with their exercises. `null` = the P14.5
     * built-in rotation; a list (even an empty one) = rotate through these, and a `STRENGTH_*`
     * type none of them maps to is not suggested at all.
     */
    val strengthPool: List<StrengthWorkout>? = null,
    /** P19: the workout most recently accepted per session type, for the pool's rotation. */
    val lastWorkoutIdBySessionType: Map<SessionType, Long> = emptyMap(),
) {
    val todayDay: Long get() = today.toEpochDays()

    /** Exclusive end of the horizon: suggestions live in `[todayDay, horizonEndDay)`. */
    val horizonEndDay: Long get() = todayDay + horizonDays

    /** Every day the grid spans, ascending. */
    val horizonDaysList: List<Long> get() = (todayDay until horizonEndDay).toList()
}
