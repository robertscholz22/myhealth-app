package com.myhealth.ui.strength

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/**
 * Backs [WorkoutsScreen] (PLAN §4.2 "Strength workouts", P14.7, More entry): the built-in +
 * user-created workouts, seeded once on open ([seed], `AppGraph.strengthWorkoutSeeder::seed` — a
 * plain suspend function, not the concrete `data/` seeder type, so `ArchitectureTest` stays green).
 */
class WorkoutsViewModel(
    private val strengthRepo: StrengthRepository,
    private val planRepo: PlanRepository,
    private val seed: suspend () -> Int,
    private val clock: PlatformClock,
) : ViewModel() {

    private val action = MutableStateFlow(WorkoutsAction())

    /**
     * The first visit seeds the built-ins one row at a time; showing the list before that is done
     * made the LazyColumn anchor on the first row it saw ("Upper A") and open scrolled past the
     * rows sorted above it. The list appears once, complete.
     */
    private val seeded = MutableStateFlow(false)

    val state: StateFlow<WorkoutsUiState> = combine(
        strengthRepo.observeAll(),
        action,
        seeded,
    ) { workouts, act, isSeeded ->
        WorkoutsUiState(
            isLoading = !isSeeded,
            workouts = if (isSeeded) workouts else emptyList(),
            pendingDeleteId = act.pendingDeleteId,
            planForDayId = act.planForDayId,
            message = act.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WorkoutsUiState())

    init {
        viewModelScope.launch {
            try {
                seed()
            } finally {
                seeded.value = true
            }
        }
    }

    fun duplicate(workoutId: Long) {
        viewModelScope.launch {
            val original = strengthRepo.getById(workoutId) ?: return@launch
            val now = clock.millis()
            val copy = original.copy(
                id = 0L,
                name = nextCopyName(original.name),
                templateId = null,
                isBuiltIn = false,
                exercises = original.exercises.map { it.copy(id = 0L, workoutId = 0L) },
                createdAtMillis = now,
                updatedAtMillis = now,
            )
            strengthRepo.upsertWorkout(copy)
        }
    }

    /** P19: the "Use in suggestions" checkbox; the next Generate rotates through checked ones only. */
    fun setUseInSuggestions(workoutId: Long, use: Boolean) {
        viewModelScope.launch { strengthRepo.setUseInSuggestions(workoutId, use) }
    }

    fun requestDelete(workoutId: Long) = action.set { it.copy(pendingDeleteId = workoutId) }

    fun cancelDelete() = action.set { it.copy(pendingDeleteId = null) }

    fun confirmDelete() {
        val id = action.value.pendingDeleteId ?: return
        action.set { it.copy(pendingDeleteId = null) }
        viewModelScope.launch { strengthRepo.deleteWorkout(id) }
    }

    fun requestPlanForDay(workoutId: Long) = action.set { it.copy(planForDayId = workoutId) }

    fun cancelPlanForDay() = action.set { it.copy(planForDayId = null) }

    /** "Plan for a day" (§4.2): a planned `STRENGTH_*` session on [day] carrying [workoutId]. */
    fun planForDay(workoutId: Long, day: LocalDate) {
        action.set { it.copy(planForDayId = null) }
        viewModelScope.launch {
            val workout = strengthRepo.getById(workoutId) ?: return@launch
            val planId = planRepo.observeActivePlan().first()?.id
            val now = clock.millis()
            val session = PlannedSession(
                id = 0L,
                planId = planId,
                day = day.toEpochDays(),
                startMinuteOfDay = null,
                sportType = SportType.STRENGTH,
                sessionType = sessionTypeFor(workout.kind),
                intensity = Intensity.MODERATE,
                targetDurationMin = workout.estimatedMinutes,
                targetDistanceMeters = null,
                targetPaceSecPerKm = null,
                estimatedTrimp = null,
                description = null,
                rationale = null,
                status = PlannedStatus.PLANNED,
                locked = false,
                linkedActivityId = null,
                sourceSuggestionId = null,
                createdAtMillis = now,
                updatedAtMillis = now,
                workoutId = workout.id,
            )
            val result = planRepo.upsertSession(session)
            action.set {
                it.copy(
                    message = when (result) {
                        is Outcome.Ok -> UiMessage.of(Res.string.workouts_planned_message)
                        is Outcome.Err -> UiMessage.of(Res.string.workouts_plan_error)
                    },
                )
            }
        }
    }

    fun consumeMessage() = action.set { it.copy(message = null) }

    private fun nextCopyName(name: String): String {
        val existing = state.value.workouts.map { it.name }.toSet()
        var candidate = "$name (copy)"
        var n = 2
        while (candidate in existing) {
            candidate = "$name (copy $n)"
            n++
        }
        return candidate
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Transient, VM-owned dialog/snackbar state — mirrors `TrainingAction` (P6.6). */
private data class WorkoutsAction(
    val pendingDeleteId: Long? = null,
    val planForDayId: Long? = null,
    val message: UiMessage? = null,
)

private fun MutableStateFlow<WorkoutsAction>.set(transform: (WorkoutsAction) -> WorkoutsAction) {
    value = transform(value)
}

/** The `SessionType` a planned session gets from a workout's kind; `CORE`/`CUSTOM` fall back to
 * `STRENGTH_FULL` — there is no dedicated core/custom session type (§2.1) — and the three P17
 * `MOBILITY_*` kinds map onto `SessionType.MOBILITY`, which already exists and carries no load. */
fun sessionTypeFor(kind: StrengthWorkoutKind): SessionType = kind.sessionType
