package com.myhealth.ui.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.engine.suggest.GoalRules
import com.myhealth.domain.engine.suggest.Periodization
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.Feedback
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.SuggestionBatch
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.domain.model.TrainingPlan
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.GoalRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.repository.SuggestionRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.strength.SetLogRow
import com.myhealth.ui.strength.nextTimeDetails
import com.myhealth.ui.strength.toStrengthSetLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/** Everything outside the displayed week that the header needs. */
private data class TrainingContext(
    val plan: TrainingPlan?,
    val batch: SuggestionBatch?,
    val goals: List<Goal>,
    val matchWithin21Days: Boolean,
    /** POLISH-8: a calendar edit happened under the open `PROPOSED` batch. */
    val suggestionsStale: Boolean = false,
)

/** Transient, VM-owned state: the in-flight generate, its snackbar and its one-shot nav signal. */
private data class TrainingAction(
    val isGenerating: Boolean = false,
    val message: UiMessage? = null,
    val reviewReady: Boolean = false,
    val selectedDay: Long? = null,
    /** The open [SetLogSheet]'s prescriptions (P16.2), from the most recent [prepareSetLog] call —
     * `emptyMap()` until it resolves, in which case the sheet falls back to each row's own numbers. */
    val setLogPrescriptions: Map<String, ExercisePrescription> = emptyMap(),
)

/**
 * Backs [TrainingScreen] (PLAN §4.2 "Training plan", P6.6).
 *
 * One week is on screen at a time, paged by [showPreviousWeek]/[showNextWeek] around the ISO
 * Monday of today. The week itself comes from the [CalendarDay] aggregate (§2.3) so events,
 * planned sessions, completed activities and `daily_load` all arrive from a single observer.
 *
 * The phase badge prefers the phase of the latest suggestion batch — that is the phase the
 * sessions on screen were actually generated under — and falls back to [Periodization] recomputed
 * live from the active goals, the next three weeks of events and the plan's start day, so the
 * badge is still right before the first batch is ever generated. The weekly target only comes from
 * a batch whose horizon overlaps the displayed week; other weeks show no target rather than a
 * number that was never computed for them.
 */
class TrainingViewModel(
    private val planRepo: PlanRepository,
    private val suggestionRepo: SuggestionRepository,
    private val calendarRepo: CalendarRepository,
    private val goalRepo: GoalRepository,
    private val settingsRepo: SettingsRepository,
    private val strengthRepo: StrengthRepository,
    /** `AppGraph.currentBodyWeightKg` (P16.2) — what `strengthRepo.prescriptionFor` estimates an
     * unlogged exercise's load from, for [prepareSetLog]. */
    private val bodyWeightKg: suspend () -> Double,
    private val clock: PlatformClock,
) : ViewModel() {

    private val weekOffset = MutableStateFlow(0)
    private val action = MutableStateFlow(TrainingAction())

    private fun todayDay(): Long = clock.today().toEpochDays()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val week: Flow<Pair<TrainingWeek, List<com.myhealth.domain.model.DailyLoad>>> =
        weekOffset.flatMapLatest { offset ->
            val start = mondayOf(todayDay()) + offset * DAYS_PER_WEEK
            calendarRepo.observeRange(start, start + DAYS_PER_WEEK - 1).map { days ->
                TrainingWeek(
                    startDay = start,
                    offset = offset,
                    days = trainingDayRows(start, todayDay(), days),
                ) to weekLoads(start, days)
            }
        }

    private val context: Flow<TrainingContext> = combine(
        planRepo.observeActivePlan(),
        suggestionRepo.observeLatestBatch(),
        goalRepo.observeByStatus(GoalStatus.ACTIVE),
        calendarRepo.observeOccurrences(todayDay(), todayDay() + PHASE_EVENT_WINDOW_DAYS),
        suggestionRepo.observeStale(),
    ) { plan, batch, goals, events, stale ->
        TrainingContext(
            plan = plan,
            batch = batch,
            goals = goals.sortedBy { it.priority },
            matchWithin21Days = Periodization.matchWithinWindow(events, todayDay()),
            suggestionsStale = stale,
        )
    }

    val state: StateFlow<TrainingUiState> = combine(
        week,
        context,
        action,
        strengthRepo.observeAll(),
    ) { weekAndLoads, ctx, act, workouts ->
        val (currentWeek, loads) = weekAndLoads
        val planned = currentWeek.days.flatMap { it.planned }
        TrainingUiState(
            isLoading = false,
            today = todayDay(),
            week = currentWeek,
            planName = ctx.plan?.name,
            phase = phaseOf(ctx),
            outlook = GoalRules.outlook(ctx.goals, todayDay())?.let(::outlookLine),
            loads = weeklyLoadSums(planned, targetFor(currentWeek, ctx.batch), loads),
            selectedDay = act.selectedDay ?: defaultSelectedDay(currentWeek),
            isGenerating = act.isGenerating,
            suggestionsStale = ctx.suggestionsStale,
            message = act.message,
            reviewReady = act.reviewReady,
            workoutsById = workouts.associateBy { it.id },
            setLogPrescriptions = act.setLogPrescriptions,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TrainingUiState())

    // ---- week paging -----------------------------------------------------------------------

    fun showPreviousWeek() = shiftWeek(-1)

    fun showNextWeek() = shiftWeek(1)

    fun showCurrentWeek() {
        weekOffset.value = 0
        action.update { it.copy(selectedDay = null) }
    }

    fun selectDay(day: Long) = action.update { it.copy(selectedDay = day) }

    private fun shiftWeek(delta: Int) {
        weekOffset.value = (weekOffset.value + delta).coerceIn(-MAX_WEEK_OFFSET, MAX_WEEK_OFFSET)
        action.update { it.copy(selectedDay = null) }
    }

    // ---- actions ---------------------------------------------------------------------------

    /** Runs the suggester over `settings.suggestionHorizonDays` and opens the review on success. */
    fun generateSuggestions() {
        if (action.value.isGenerating) return
        viewModelScope.launch {
            action.update { it.copy(isGenerating = true, message = null) }
            val horizon = settingsRepo.settings.first().suggestionHorizonDays
            action.update {
                when (suggestionRepo.generate(horizon)) {
                    is Outcome.Ok -> it.copy(isGenerating = false, reviewReady = true)
                    is Outcome.Err -> it.copy(
                        isGenerating = false,
                        message = UiMessage.of(Res.string.training_generate_error),
                    )
                }
            }
        }
    }

    fun setLocked(sessionId: Long, locked: Boolean) = run(
        if (locked) UiMessage.of(Res.string.training_session_locked) else UiMessage.of(Res.string.training_session_unlocked),
    ) { planRepo.setSessionLocked(sessionId, locked) }

    fun markDone(sessionId: Long) = run(UiMessage.of(Res.string.training_session_marked_done)) {
        planRepo.setSessionStatus(sessionId, PlannedStatus.COMPLETED)
    }

    /**
     * Resolves today's prescription for every exercise of [workout] (P16.2) so the [SetLogSheet]
     * about to open can pre-fill its rows from it, rather than from the workout's own stored
     * numbers. Overwrites whatever a previous open left in [TrainingAction.setLogPrescriptions].
     */
    fun prepareSetLog(workout: StrengthWorkout) {
        viewModelScope.launch {
            val weight = bodyWeightKg()
            val prescriptions = workout.exercises.map { it.exerciseId }.distinct()
                .mapNotNull { id -> ExerciseCatalog.byId(id)?.let { id to strengthRepo.prescriptionFor(it, weight) } }
                .toMap()
            action.update { it.copy(setLogPrescriptions = prescriptions) }
        }
    }

    /**
     * "Mark done" on a `STRENGTH_*` session with a workout, once the [SetLogSheet] rows and
     * per-exercise [feedback] are confirmed (P14.7, P16.2): writes the set logs — [rows] already
     * excludes anything skipped — through `saveSetLogs`, which also advances the progression for
     * every exercise that carries a feedback, then marks the session done exactly like [markDone].
     * The snackbar becomes "Next time: …" when at least one exercise actually advanced, else the
     * plain "marked done" message (no rows logged, or nothing had a feedback).
     */
    fun completeStrengthSession(session: PlannedSession, rows: List<SetLogRow>, feedback: Map<String, Feedback>) {
        viewModelScope.launch {
            var message = UiMessage.of(Res.string.training_session_marked_done)
            if (rows.isNotEmpty()) {
                val now = clock.millis()
                val logs = rows.map { it.toStrengthSetLog(session.day, session.id, now, feedback[it.exerciseId]) }
                val newStates = when (val outcome = strengthRepo.saveSetLogs(logs)) {
                    is Outcome.Ok -> outcome.value
                    is Outcome.Err -> emptyMap()
                }
                if (newStates.isNotEmpty()) {
                    message = UiMessage.of(Res.string.training_next_time_format, nextTimeDetails(rows, newStates))
                }
            }
            planRepo.setSessionStatus(session.id, PlannedStatus.COMPLETED)
            action.update { it.copy(message = message, setLogPrescriptions = emptyMap()) }
        }
    }

    fun skip(sessionId: Long) = run(UiMessage.of(Res.string.training_session_skipped)) {
        planRepo.setSessionStatus(sessionId, PlannedStatus.SKIPPED)
    }

    fun reopen(sessionId: Long) = run(UiMessage.of(Res.string.training_session_reopened)) {
        planRepo.setSessionStatus(sessionId, PlannedStatus.PLANNED)
    }

    fun delete(sessionId: Long) = run(UiMessage.of(Res.string.training_session_deleted)) {
        planRepo.deleteSession(sessionId)
    }

    fun consumeMessage() = action.update { it.copy(message = null) }

    fun consumeReviewReady() = action.update { it.copy(reviewReady = false) }

    private fun run(success: UiMessage, block: suspend () -> Outcome<*>) {
        viewModelScope.launch {
            val message = when (block()) {
                is Outcome.Ok -> success
                is Outcome.Err -> UiMessage.of(Res.string.training_update_error)
            }
            action.update { it.copy(message = message) }
        }
    }

    // ---- header ----------------------------------------------------------------------------

    /**
     * The batch's phase when there is one, else §3.5.2 recomputed from goals and events. A down
     * week (P19.6) needs the load history and is only decided when suggestions are generated.
     */
    private fun phaseOf(ctx: TrainingContext): TrainingPhase {
        val batch = ctx.batch
        if (batch != null && batch.status != SuggestionStatus.SUPERSEDED) return batch.phase
        return Periodization.phase(
            daysToRace = Periodization.daysToRace(ctx.goals, todayDay()),
            matchWithin21Days = ctx.matchWithin21Days,
        )
    }

    /** A batch's weekly budget only describes the week its horizon covers. */
    private fun targetFor(week: TrainingWeek, batch: SuggestionBatch?): Double {
        if (batch == null) return 0.0
        val weekEnd = week.startDay + DAYS_PER_WEEK
        val overlaps = batch.horizonStartDay < weekEnd && batch.horizonEndDay > week.startDay
        return if (overlaps) batch.weeklyLoadTarget else 0.0
    }

    private fun defaultSelectedDay(week: TrainingWeek): Long {
        val today = todayDay()
        return if (today in week.startDay until week.startDay + DAYS_PER_WEEK) today else week.startDay
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** How far the fallback phase computation looks for a match (§3.5.2's 21-day window). */
        const val PHASE_EVENT_WINDOW_DAYS = 21L

        /** The board pages a year either way; further than that is a different plan. */
        const val MAX_WEEK_OFFSET = 52
    }
}
