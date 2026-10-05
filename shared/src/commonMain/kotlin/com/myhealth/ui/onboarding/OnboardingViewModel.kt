package com.myhealth.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.Sex
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.sync.SyncScheduler
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.encodePreferredSports
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/**
 * Drives the 3-step onboarding form (§4.2). Saves a [Profile] (id 1) and an initial
 * [BodyMeasurement] (source `MANUAL`), then flags `hasCompletedOnboarding` in settings and emits
 * [OnboardingEvent.Saved] so the nav host can pop to `TodayRoute`.
 */
class OnboardingViewModel(
    private val profileRepo: ProfileRepository,
    private val bodyRepo: BodyRepository,
    private val settingsRepo: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val clock: PlatformClock,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private fun today(): LocalDate = clock.today()

    fun updateDraft(transform: (OnboardingDraft) -> OnboardingDraft) {
        _state.update {
            val draft = transform(it.draft)
            it.copy(draft = draft, errors = validate(draft, today()))
        }
    }

    /** Advances to the next step if the current step validates, or saves on the last step. */
    fun next() {
        val current = _state.value
        val errors = validate(current.draft, today())
        if (current.step.fields.any { it in errors }) {
            _state.update { it.copy(errors = errors) }
            return
        }
        val steps = OnboardingStep.entries
        val index = steps.indexOf(current.step)
        if (index < steps.lastIndex) {
            _state.update { it.copy(step = steps[index + 1], errors = errors) }
        } else {
            save()
        }
    }

    fun back() {
        val steps = OnboardingStep.entries
        val index = steps.indexOf(_state.value.step)
        if (index > 0) _state.update { it.copy(step = steps[index - 1]) }
    }

    private fun save() {
        val draft = _state.value.draft
        val errors = validate(draft, today())
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            val now = clock.millis()

            val profile = Profile(
                displayName = draft.displayName.trim(),
                sex = draft.sex,
                birthDay = requireNotNull(draft.birthDay).toEpochDays(),
                heightCm = requireNotNull(draft.heightCm),
                neatLevel = draft.neatLevel,
                goalWeightKg = draft.goalWeightKg,
                goalPaceKgPerWeek = draft.goalPaceKgPerWeek ?: 0.0,
                sleepTargetHours = draft.sleepTargetHours ?: 8.0,
                preferredSportsJson = encodePreferredSports(draft.sessionsPerWeek),
                mobilityOnRestDays = draft.mobilityOnRestDays,
                createdAtMillis = now,
                updatedAtMillis = now,
            )

            when (val profileOutcome = profileRepo.upsert(profile)) {
                is Outcome.Err -> {
                    _state.update {
                        it.copy(isSaving = false, saveError = UiMessage.of(Res.string.onboarding_save_profile_error))
                    }
                    return@launch
                }
                is Outcome.Ok -> Unit
            }

            val measurement = BodyMeasurement(
                measuredAtMillis = now,
                day = today().toEpochDays(),
                weightKg = draft.weightKg,
                bodyFatPercent = null,
                muscleMassKg = null,
                boneMassKg = null,
                bodyWaterPercent = null,
                source = ActivitySource.MANUAL,
            )

            when (val bodyOutcome = bodyRepo.insert(measurement)) {
                is Outcome.Err -> {
                    _state.update {
                        it.copy(isSaving = false, saveError = UiMessage.of(Res.string.onboarding_save_weight_error))
                    }
                    return@launch
                }
                is Outcome.Ok -> Unit
            }

            settingsRepo.setHasCompletedOnboarding(true)
            // P11.1/P11.3: the cycle tracker is on by default for the users it was requested for,
            // via the switch on step 3 (shown only for FEMALE; the draft default is `true`).
            if (draft.sex == Sex.FEMALE) settingsRepo.setCycleTrackingEnabled(draft.cycleTrackingEnabled)
            // First profile + weight: compute the whole target window right away (P4.12).
            syncScheduler.requestTargetRecompute()
            _state.update { it.copy(isSaving = false) }
            _events.trySend(OnboardingEvent.Saved)
        }
    }
}
