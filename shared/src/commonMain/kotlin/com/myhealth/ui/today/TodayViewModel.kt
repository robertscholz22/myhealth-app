package com.myhealth.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.engine.calendar.EventActivityLinker
import com.myhealth.domain.engine.calendar.LinkProposal
import com.myhealth.domain.engine.strength.MuscleLoadEngine
import com.myhealth.domain.engine.strength.MuscleLoadState
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.DailyHealthSummary
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.SyncState
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.CycleRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.LoadRepository
import com.myhealth.domain.repository.MealRepository
import com.myhealth.domain.repository.NutritionRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.repository.SuggestionRepository
import com.myhealth.domain.repository.SyncStateRepository
import com.myhealth.sync.SyncScheduler
import com.myhealth.sync.SyncWorkState
import com.myhealth.ui.load.resolveMuscleLoadState
import com.myhealth.ui.zones.resolveHrZoneModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myhealth.ui.nutrition.intakeOf
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/** The four fields combined ahead of [TodaySyncData] — kept typed rather than an `Array<Any?>`
 * cast, since `combine` only has typed overloads up to five flows (PLAN §1.4). */
private data class TodayCoreData(
    val activities: List<ActivitySummary>,
    val latestWeight: BodyMeasurement?,
    val profile: Profile?,
    val health: DailyHealthSummary?,
)

private data class TodaySyncData(
    val sleep: SleepRecord?,
    val syncStates: List<SyncState>,
    val syncNow: SyncWorkState,
)

/** Today's nutrition card (P4.12): the day's snapshot target against what has been logged. */
private data class TodayNutrition(val target: NutritionTarget?, val intake: MacroTotals)

/** Today's recovery + load cards (P5.8): the latest cached `daily_load` row and the last 7 days'
 * summed TRIMP (`daily_load` has no rolling-weekly column of its own). */
private data class TodayLoad(val latest: DailyLoad?, val weeklyTrimp: Double)

/** Today's cycle card (P11.3): whether it should show at all, and today's status. */
private data class TodayCycle(val trackingEnabled: Boolean, val status: CycleStatus?)

/** The five non-load flows combined ahead of the final state, again to stay within `combine`'s
 * five-flow typed overload once [TodayLoad] joins as the sixth (PLAN §1.4). */
private data class CoreAndSync(
    val core: TodayCoreData,
    val sync: TodaySyncData,
    val links: List<LinkProposal>,
    val food: TodayNutrition,
    val cycle: TodayCycle,
)

/** Today's plan card (P6.6/P6.7): what is planned, or the best proposal still awaiting review. */
private data class TodayPlan(
    val planned: List<PlannedSession>,
    val suggested: SuggestedSession?,
    /** POLISH-8: the open batch predates a calendar change. */
    val stale: Boolean = false,
)

private const val WEEKLY_TRIMP_WINDOW_DAYS = 7L

/**
 * Backs [TodayScreen] (PLAN §4.2 Today (Home), P2.10/P5.8): today's activities, latest weight vs
 * goal, today's [DailyHealthSummary], last night's sleep, the nutrition card of P4.12, the
 * recovery/load cards of P5.8, and the sync-status banner, plus the plan card of P6.6: today's
 * planned sessions, or — when the day has none — the best-scoring suggestion for today from the
 * batch still awaiting review (P6.7).
 *
 * [NutritionRepository.ensureTarget] is called once for today on construction, so the card has a
 * target even if the recompute worker has not run yet (it is a no-op when nothing changed).
 */
class TodayViewModel(
    activityRepo: ActivityRepository,
    bodyRepo: BodyRepository,
    profileRepo: ProfileRepository,
    healthRepo: HealthRepository,
    mealRepo: MealRepository,
    private val nutritionRepo: NutritionRepository,
    syncStateRepo: SyncStateRepository,
    private val syncScheduler: SyncScheduler,
    private val calendarRepo: CalendarRepository,
    loadRepo: LoadRepository,
    private val planRepo: PlanRepository,
    private val suggestionRepo: SuggestionRepository,
    private val cycleRepo: CycleRepository,
    private val strengthRepo: StrengthRepository,
    clock: PlatformClock,
) : ViewModel() {

    private val today = clock.today().toEpochDays()

    init {
        viewModelScope.launch { nutritionRepo.ensureTarget(today) }
    }

    /** Dismissals are VM-only (not persisted): a re-open of the app shows the suggestion again. */
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())

    private val linkSuggestions = combine(
        calendarRepo.observeLinkProposals(today - 7, today),
        dismissed,
    ) { proposals, dismissedKeys ->
        proposals.filter {
            it.confidence >= EventActivityLinker.PROPOSE_THRESHOLD && linkProposalKey(it) !in dismissedKeys
        }
    }

    private val core = combine(
        activityRepo.observeRange(today, today),
        bodyRepo.observeLatest(),
        profileRepo.observeProfile(),
        healthRepo.observeDay(today),
    ) { activities, latestWeight, profile, health -> TodayCoreData(activities, latestWeight, profile, health) }

    private val nutrition = combine(
        nutritionRepo.observeTarget(today),
        mealRepo.observeDay(today),
    ) { target, logs -> TodayNutrition(target, intakeOf(logs)) }

    private val syncData = combine(
        healthRepo.observeLatestSleep(),
        syncStateRepo.observeAll(),
        syncScheduler.observeState(),
    ) { sleep, syncStates, syncNow -> TodaySyncData(sleep, syncStates, syncNow) }

    private val load = combine(
        loadRepo.observeLatest(),
        loadRepo.observeRange(today - WEEKLY_TRIMP_WINDOW_DAYS + 1, today),
    ) { latest, week -> TodayLoad(latest, week.sumOf { it.trimp }) }

    private val cycle: Flow<TodayCycle> = combine(
        cycleRepo.isTrackingEnabled(),
        cycleRepo.observeStatus(today),
    ) { trackingEnabled, status -> TodayCycle(trackingEnabled, status) }

    /** The best still-open suggestion for today, from the latest batch that is still `PROPOSED`. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val suggestedToday: Flow<SuggestedSession?> =
        suggestionRepo.observeLatestBatch().flatMapLatest { batch ->
            if (batch == null || batch.status != SuggestionStatus.PROPOSED) {
                flowOf(null)
            } else {
                suggestionRepo.observeSessions(batch.id).map { sessions ->
                    sessions
                        .filter { it.day == today && it.status == SuggestionStatus.PROPOSED }
                        .maxByOrNull { it.score }
                }
            }
        }

    private val plan = combine(
        planRepo.observeSessions(today, today),
        suggestedToday,
        suggestionRepo.observeStale(),
    ) { planned, suggested, stale -> TodayPlan(planned, suggested, stale) }

    /** Today's [com.myhealth.domain.engine.strength.MuscleLoadState] (P14.8) — resolved the same
     * way [LoadViewModel] resolves it, since `ui/` may not import `data/repository`'s own
     * assembly (`ArchitectureTest`). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val muscleLoad = combine(
        activityRepo.observeRange(today - MuscleLoadEngine.WINDOW_DAYS, today),
        loadRepo.observeLatest(),
    ) { activities, latest -> activities to (latest?.ctl ?: 0.0) }
        .flatMapLatest { (activities, ctl) ->
            flow { emit(resolveMuscleLoadState(activities, ctl, today, planRepo, strengthRepo)) }
        }

    private val coreAndSync = combine(core, syncData, linkSuggestions, nutrition, cycle) { c, s, links, food, cyc ->
        CoreAndSync(c, s, links, food, cyc)
    }

    /** NOTE-21: the chip's zone model, resolved like the Zones screen (resting HR + observed max). */
    private val zoneModel = profileRepo.observeProfile().map { profile ->
        resolveHrZoneModel(profile, today, healthRepo, activityRepo)
    }

    val state: StateFlow<TodayUiState> = combine(
        coreAndSync, load, plan, combine(muscleLoad, zoneModel) { m, z -> m to z },
    ) { cs, l, p, mz ->
        val m = mz.first
        TodayUiState(
            isLoading = false,
            day = today,
            activities = cs.core.activities,
            latestWeight = cs.core.latestWeight,
            goalWeightKg = cs.core.profile?.goalWeightKg,
            healthSummary = cs.core.health,
            sleep = cs.sync.sleep,
            lastSyncSuccessAtMillis = cs.sync.syncStates.mapNotNull { it.lastSuccessAtMillis }.maxOrNull(),
            lastSyncError = cs.sync.syncStates.firstOrNull { st ->
                st.lastError != null && (st.lastErrorAtMillis ?: 0L) > (st.lastSuccessAtMillis ?: 0L)
            }?.lastError,
            isSyncing = cs.sync.syncNow == SyncWorkState.Running,
            linkSuggestions = cs.links,
            target = cs.food.target,
            intake = cs.food.intake,
            latestLoad = l.latest,
            weeklyTrimp = l.weeklyTrimp,
            plannedToday = p.planned,
            suggestedToday = p.suggested.takeIf { p.planned.isEmpty() },
            suggestionsStale = p.stale,
            cycleTrackingEnabled = cs.cycle.trackingEnabled,
            cycleStatus = cs.cycle.status,
            hrZoneModel = mz.second,
            muscleLoad = m,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun syncNow() {
        syncScheduler.syncNow()
    }

    /** "Done" on the plan card (§4.2 Today, section 2). */
    fun markPlannedDone(sessionId: Long) {
        viewModelScope.launch { planRepo.setSessionStatus(sessionId, PlannedStatus.COMPLETED) }
    }

    /** Accepting a suggestion (P3.7): links it (upgrading a `SOCCER_MATCH` event's activity sport
     * happens inside the repository) and dismisses the card row. */
    fun acceptSuggestion(proposal: LinkProposal) {
        viewModelScope.launch {
            calendarRepo.linkActivity(proposal.eventOccurrence.eventId, proposal.activity.id, LinkMethod.AUTO_ACCEPTED)
            dismissed.update { it + linkProposalKey(proposal) }
        }
    }

    fun dismissSuggestion(proposal: LinkProposal) {
        dismissed.update { it + linkProposalKey(proposal) }
    }
}
