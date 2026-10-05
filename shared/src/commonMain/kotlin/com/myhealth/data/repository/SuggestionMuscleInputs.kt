package com.myhealth.data.repository

import com.myhealth.domain.engine.strength.MuscleLoadEngine
import com.myhealth.domain.engine.strength.MuscleLoadInput
import com.myhealth.domain.engine.strength.MuscleLoadState
import com.myhealth.domain.engine.strength.MuscleSession
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.StrengthRepository
import kotlinx.coroutines.flow.first

/**
 * The two P14.5 inputs of `SuggestionInput` (PLAN §3.12.4 / §3.12.5): today's per-muscle-group load
 * and the built-in workout most recently accepted for each kind.
 *
 * Both are "unknown" by default, and unknown switches the whole strength layer off — `C15` never
 * fires, `Scorer.muscleBonus` is `0.0`, no `muscle=` line is hashed and no template is proposed
 * (tests `sug39` / `sug40`).
 */
data class SuggestionMuscleInputs(
    val muscleLoad: MuscleLoadState? = null,
    val lastAcceptedTemplateByKind: Map<StrengthWorkoutKind, String> = emptyMap(),
    /** P19: the newest planned workout (any, built-in or own) per session type — the pool rotation. */
    val lastWorkoutIdBySessionType: Map<SessionType, Long> = emptyMap(),
) {
    companion object {
        val EMPTY: SuggestionMuscleInputs = SuggestionMuscleInputs()
    }
}

/**
 * Assembles [SuggestionMuscleInputs] the way [SuggestionPaceResolver] assembles the pace ones
 * (P14.3's pattern): the last [MuscleLoadEngine.WINDOW_DAYS] days of completed activities, joined
 * to the planned sessions that claimed them (`linkedActivityId`) for the session type and, through
 * `plannedSession.workoutId`, to the actual exercise list.
 *
 * A Garmin strength activity carries no exercise detail, which is exactly the case §3.12.4's
 * generic `STRENGTH_*` tables exist for: no link, no workout, the session type decides — and when
 * not even that is known, the full-body mean does.
 *
 * The resolver is [isEnabled] `false` without a [StrengthRepository] (the P6.5 test harness and
 * every caller that predates P14.5) and then reads nothing at all.
 *
 * Ambiguity note (§3.12.5 does not say where the alternation's "most recently accepted template"
 * comes from): it is read off the planned sessions themselves — the newest session carrying a
 * `workoutId` whose workout is a built-in, per [StrengthWorkoutKind] — because that is what
 * `accept` writes, so a user who edits their copy of `UPPER_A` still alternates off it.
 */
class SuggestionMuscleResolver(
    private val activityRepo: ActivityRepository,
    private val planRepo: PlanRepository,
    private val strengthRepo: StrengthRepository?,
) {

    val isEnabled: Boolean get() = strengthRepo != null

    suspend fun resolve(todayDay: Long, ctl: Double): SuggestionMuscleInputs {
        val repo = strengthRepo ?: return SuggestionMuscleInputs.EMPTY
        val fromDay = todayDay - MuscleLoadEngine.WINDOW_DAYS
        val activities = activityRepo.observeRange(fromDay, todayDay).first()
        val planned = planRepo.getSessions(fromDay, todayDay + TEMPLATE_LOOKAHEAD_DAYS)
        val workouts = mutableMapOf<Long, StrengthWorkout?>()
        val linked = planned
            .filter { it.day <= todayDay && it.linkedActivityId != null }
            .associateBy { it.linkedActivityId!! }
        val sessions = activities.map { activity ->
            val link = linked[activity.id]
            MuscleSession(
                day = activity.day,
                sportGroup = activity.sportGroup,
                sessionType = link?.sessionType,
                trimp = activity.trimp ?: 0.0,
                workout = link?.workoutId?.let { id -> workouts.getOrPut(id) { repo.getById(id) } },
            )
        }
        return SuggestionMuscleInputs(
            muscleLoad = MuscleLoadEngine.compute(
                MuscleLoadInput(today = todayDay, ctl = ctl, sessions = sessions),
            ),
            lastAcceptedTemplateByKind = lastTemplates(planned, repo, workouts),
            lastWorkoutIdBySessionType = planned
                .filter { it.workoutId != null }
                .sortedWith(compareBy({ it.day }, { it.id }))
                .associate { it.sessionType to it.workoutId!! },
        )
    }

    /** The newest built-in per kind, by `(day, id)` so the answer never depends on row order. */
    private suspend fun lastTemplates(
        planned: List<PlannedSession>,
        repo: StrengthRepository,
        workouts: MutableMap<Long, StrengthWorkout?>,
    ): Map<StrengthWorkoutKind, String> {
        val result = mutableMapOf<StrengthWorkoutKind, String>()
        planned
            .filter { it.workoutId != null }
            .sortedWith(compareBy({ it.day }, { it.id }))
            .forEach { session ->
                val id = session.workoutId ?: return@forEach
                val workout = workouts.getOrPut(id) { repo.getById(id) } ?: return@forEach
                val templateId = workout.templateId ?: return@forEach
                result[workout.kind] = templateId
            }
        return result
    }

    companion object {
        /**
         * Accepted strength sessions sit in the *future*, so the alternation looks a fortnight past
         * today as well as a fortnight back.
         */
        const val TEMPLATE_LOOKAHEAD_DAYS: Long = 14L
    }
}
