package com.myhealth.ui.strength

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.engine.strength.EquipmentSetCodec
import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock

/**
 * Backs [ExercisesScreen] and [ExerciseDetailScreen] (PLAN §4.2 "Exercises"/"Exercise detail",
 * P14.7, P16.2). The catalog is a constant object, so this class holds only the filter chips
 * (query, equipment, pattern, muscle, "only my equipment") plus the live workout list the "Add to
 * workout…" picker needs on both screens, and the Progression card's own small, separately loaded
 * state — there is deliberately no separate `ExerciseDetailViewModel`.
 *
 * Seeds the six built-in templates on open (idempotent, §3.12.3) — the Exercises screen is as
 * likely an entry point into "add this to a workout" as the Workouts screen itself. [seed] is
 * `AppGraph.strengthWorkoutSeeder::seed` and [bodyWeightKg] is `AppGraph.currentBodyWeightKg`, both
 * handed in as plain suspend functions — `ui/` may not import `com.myhealth.data.*`
 * (`ArchitectureTest`), so no concrete `data/` type ever appears here.
 */
class ExercisesViewModel(
    private val strengthRepo: StrengthRepository,
    private val profileRepo: ProfileRepository,
    private val seed: suspend () -> Int,
    private val bodyWeightKg: suspend () -> Double,
    private val clock: PlatformClock,
) : ViewModel() {

    private val filters = MutableStateFlow(ExercisesUiState())
    private val progressionCard = MutableStateFlow(ProgressionCardState())

    val state: StateFlow<ExercisesUiState> = combine(
        filters,
        strengthRepo.observeAll(),
        profileRepo.observeProfile(),
    ) { filter, workouts, profile ->
        filter.copy(workouts = workouts, myEquipment = EquipmentSetCodec.decode(profile?.availableEquipmentJson))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ExercisesUiState())

    /** [ExerciseDetailScreen]'s Progression card (P16.2) — loaded on demand by [loadProgressionCard]
     * rather than combined into [state], since it is per-exercise and the list screen never needs it. */
    val progressionCardState: StateFlow<ProgressionCardState> = progressionCard.asStateFlow()

    init {
        viewModelScope.launch { seed() }
    }

    fun setQuery(query: String) = filters.update { it.copy(query = query) }

    /** The P17 kind chip row (All/Strength/Mobility). */
    fun setKind(kind: com.myhealth.domain.engine.strength.ExerciseKind?) = filters.update { it.copy(kind = kind) }

    fun setEquipment(equipment: Equipment?) = filters.update { it.copy(equipment = equipment) }

    fun setPattern(pattern: MovementPattern?) = filters.update { it.copy(pattern = pattern) }

    fun setMuscle(muscle: MuscleGroup?) = filters.update { current ->
        current.copy(muscle = if (current.muscle == muscle) null else muscle)
    }

    fun setOnlyMyEquipment(onlyMyEquipment: Boolean) = filters.update { it.copy(onlyMyEquipment = onlyMyEquipment) }

    fun clearFilters() = filters.update { it.copy(equipment = null, pattern = null, muscle = null) }

    /**
     * Loads [ExerciseDetailScreen]'s Progression card for [exerciseId] (P16.2): today's
     * prescription (stored state, or the body-weight estimate) plus up to 10 recent logged
     * sessions ([recentSessionLines]). A dropped catalog id leaves the card empty.
     */
    fun loadProgressionCard(exerciseId: String) {
        val exercise = ExerciseCatalog.byId(exerciseId) ?: return
        viewModelScope.launch {
            val weight = bodyWeightKg()
            val prescription = strengthRepo.prescriptionFor(exercise, weight)
            val progress = strengthRepo.getProgress(exerciseId)
            val recent = strengthRepo.getRecentSetLogs(exerciseId, PROGRESSION_HISTORY_ROW_LIMIT)
            progressionCard.value = ProgressionCardState(
                prescription = prescription,
                updatedDay = progress?.updatedDay,
                sessions = recentSessionLines(recent),
            )
        }
    }

    /**
     * Appends [exerciseId] as a new last row of [workoutId] with sensible defaults (3 sets, 10
     * reps or a 30 s hold for a timed exercise, bodyweight flagged off the catalog's own
     * equipment) and saves the whole workout — "Add to workout…" (§4.2).
     */
    fun addToWorkout(exerciseId: String, workoutId: Long) {
        val exercise = ExerciseCatalog.byId(exerciseId) ?: return
        viewModelScope.launch {
            val workout = strengthRepo.getById(workoutId) ?: return@launch
            val row = StrengthWorkoutExercise(
                id = 0L,
                workoutId = workoutId,
                orderIndex = workout.exercises.size,
                exerciseId = exercise.id,
                sets = DEFAULT_SETS,
                reps = if (exercise.isTimed) null else DEFAULT_REPS,
                seconds = if (exercise.isTimed) DEFAULT_HOLD_SECONDS else null,
                isBodyweight = exercise.isBodyweightOnly,
            )
            val now = clock.millis()
            val updated = workout.copy(exercises = workout.exercises + row, updatedAtMillis = now)
            when (strengthRepo.upsertWorkout(updated)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> Unit // The editor is where a real validation error would surface.
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_SETS = 3
        const val DEFAULT_REPS = 10
        const val DEFAULT_HOLD_SECONDS = 30

        /** Rows fetched to find up to 10 distinct *sessions* (days) — a set log has several rows
         * per session, so the row limit is generous rather than exactly 10 (P16.2). */
        const val PROGRESSION_HISTORY_ROW_LIMIT = 60
    }
}

private fun MutableStateFlow<ExercisesUiState>.update(transform: (ExercisesUiState) -> ExercisesUiState) {
    value = transform(value)
}
