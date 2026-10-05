package com.myhealth.ui.onboarding

import com.myhealth.ui.common.UiMessage

/** ViewModel state for [OnboardingScreen] (§1.4: plain `data class` behind a `StateFlow`). */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.IDENTITY,
    val draft: OnboardingDraft = OnboardingDraft(),
    val errors: Map<OnboardingField, UiMessage> = emptyMap(),
    val isSaving: Boolean = false,
    val saveError: UiMessage? = null,
) {
    /** Whether the current step's own fields are all valid — gates the Next/Finish button. */
    val canContinue: Boolean get() = step.fields.none { it in errors }

    val isLastStep: Boolean get() = step == OnboardingStep.PREFERENCES
}

/** One-shot navigation signal (§1.4): emitted once the profile + initial weight are saved. */
sealed interface OnboardingEvent {
    data object Saved : OnboardingEvent
}
