package com.myhealth.ui.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.SuggestionBatch
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.repository.SuggestionRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.formatResourceString
import com.myhealth.ui.common.mathRound
import com.myhealth.ui.zones.lightweightHrZoneModel
import com.myhealth.ui.zones.resolveHrZoneModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/** Transient state the review screen owns: per-card toggles plus the in-flight accept/regenerate. */
private data class ReviewAction(
    val accepted: Map<Long, Boolean> = emptyMap(),
    val isWorking: Boolean = false,
    val message: UiMessage? = null,
    val done: Boolean = false,
)

/**
 * Backs [SuggestionReviewScreen] (PLAN §4.2 "Suggestion review", P6.7).
 *
 * Observes the latest [SuggestionBatch] and its sessions. Every card starts accepted — the batch
 * is a proposal the owner prunes, not a form to fill in — so the toggle map only ever records the
 * *rejections*. "Accept selected" therefore does both halves of the review in one go:
 * [SuggestionRepository.accept] for the ticked sessions and [SuggestionRepository.reject] for the
 * rest, which leaves no suggestion sitting in `PROPOSED` limbo.
 */
class SuggestionReviewViewModel(
    private val suggestionRepo: SuggestionRepository,
    private val settingsRepo: SettingsRepository,
    private val profileRepo: ProfileRepository,
    private val clock: PlatformClock,
    private val planRepo: PlanRepository? = null,
    private val healthRepo: HealthRepository? = null,
    private val activityRepo: ActivityRepository? = null,
) : ViewModel() {

    private val action = MutableStateFlow(ReviewAction())

    /**
     * POLISH-9: only a `PROPOSED` batch is a proposal. Once the review has been saved the batch is
     * `ACCEPTED`/`REJECTED` and this screen must stop offering it, even though
     * `observeLatestBatch` still returns it for the Training phase badge.
     */
    private val batch: Flow<SuggestionBatch?> = suggestionRepo.observeLatestBatch()
        .map { it?.takeIf { batch -> batch.status == SuggestionStatus.PROPOSED } }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sessions: Flow<List<SuggestedSession>> = batch.flatMapLatest { current ->
        if (current == null) flowOf(emptyList()) else suggestionRepo.observeSessions(current.id)
    }

    /** BUG-10: how many unlocked planned sessions in the horizon accepting would replace. */
    private val replaceable: Flow<Int> = batch.map { current ->
        current?.let { suggestionRepo.countReplaceableSessions(it.id) } ?: 0
    }

    /**
     * NOTE-19: the load already fixed in the horizon — locked sessions and sessions the user
     * planned by hand (BUG-15) — which the engine subtracts from the weekly target before it
     * places anything. When it reaches the target the proposal is fillers only, and the header
     * has to say why.
     */
    private val fixedLoad: Flow<Double> = batch.map { current ->
        val repo = planRepo
        if (current == null || repo == null) 0.0 else repo.getSessions(current.horizonStartDay, current.horizonEndDay - 1)
            .filter { it.locked || it.sourceSuggestionId == null }
            .sumOf { it.estimatedTrimp ?: 0.0 }
    }

    /** NOTE-20/21: the chip's zone model is resolved exactly like the Zones screen's. */
    private val zoneModel: Flow<HrZoneModel?> = profileRepo.observeProfile().map { profile ->
        val today = clock.today()
        val health = healthRepo
        val activities = activityRepo
        if (health != null && activities != null) {
            resolveHrZoneModel(profile, today.toEpochDays(), health, activities)
        } else {
            lightweightHrZoneModel(profile, today)
        }
    }

    val state: StateFlow<SuggestionReviewUiState> =
        combine(batch, sessions, action, replaceable, combine(fixedLoad, zoneModel) { f, z -> f to z }) { current, rows, act, count, extra ->
            SuggestionReviewUiState(
                isLoading = false,
                batch = current,
                rows = suggestionRows(current, rows),
                accepted = act.accepted,
                replaceableCount = count,
                isWorking = act.isWorking,
                message = act.message,
                done = act.done,
                hrZoneModel = extra.second,
                fixedLoad = extra.first,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            SuggestionReviewUiState(),
        )

    fun toggle(sessionId: Long) = action.update { current ->
        val next = !(current.accepted[sessionId] ?: true)
        current.copy(accepted = current.accepted + (sessionId to next))
    }

    fun acceptAll() = action.update { current ->
        current.copy(accepted = emptyMap())
    }

    /** Accepts what is still ticked and rejects the rest, then signals the screen to go back. */
    fun acceptSelected() {
        if (action.value.isWorking) return
        val current = state.value
        viewModelScope.launch {
            action.update { it.copy(isWorking = true, message = null) }
            val accepted = suggestionRepo.accept(current.selectedIds)
            val rejected = if (current.rejectedIds.isEmpty()) {
                Outcome.Ok(Unit)
            } else {
                suggestionRepo.reject(current.rejectedIds)
            }
            action.update {
                if (accepted is Outcome.Ok && rejected is Outcome.Ok) {
                    it.copy(isWorking = false, done = true)
                } else {
                    it.copy(isWorking = false, message = UiMessage.of(Res.string.review_save_error))
                }
            }
        }
    }

    /** Runs the suggester again; the new batch supersedes this one and replaces the list. */
    fun regenerate() {
        if (action.value.isWorking) return
        viewModelScope.launch {
            action.update { it.copy(isWorking = true, message = null) }
            val horizon = settingsRepo.settings.first().suggestionHorizonDays
            action.update {
                when (suggestionRepo.generate(horizon)) {
                    is Outcome.Ok -> it.copy(isWorking = false, accepted = emptyMap())
                    is Outcome.Err -> it.copy(
                        isWorking = false,
                        message = UiMessage.of(Res.string.review_generate_error),
                    )
                }
            }
        }
    }

    fun consumeMessage() = action.update { it.copy(message = null) }

    fun consumeDone() = action.update { it.copy(done = false) }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * The screen's read of the batch header: `"Build · target 620 AU · 540 AU suggested"`. The words
 * around the numbers are passed in already resolved, since this is a plain (non-`@Composable`)
 * function and cannot call `stringResource` itself.
 */
fun SuggestionReviewUiState.headerLine(
    targetLabel: String,
    suggestedLabel: String,
    restDaySingular: String,
    restDayPlural: String,
    replacesSingular: String = "",
    replacesPlural: String = "",
    fixedCoversFormat: String = "",
): String = buildList {
    phase?.let { add(it.label()) }
    if (weeklyTarget > 0.0) add("$targetLabel ${mathRound(weeklyTarget)} AU")
    add("${mathRound(totalSuggestedLoad)} AU $suggestedLabel")
    if (restDayCount > 0) {
        add("$restDayCount ${if (restDayCount == 1) restDaySingular else restDayPlural}")
    }
    if (replaceableCount > 0 && replacesPlural.isNotEmpty()) {
        add("$replaceableCount ${if (replaceableCount == 1) replacesSingular else replacesPlural}")
    }
    // NOTE-19: when the fixed sessions alone reach the target, say why the week is fillers only.
    if (fixedCoversFormat.isNotEmpty() && weeklyTarget > 0.0 && fixedLoad >= weeklyTarget) {
        add(formatResourceString(fixedCoversFormat, arrayOf(mathRound(fixedLoad), mathRound(weeklyTarget))))
    }
}.joinToString(" · ")
