package com.myhealth.ui.onboarding

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.SportGroup
import com.myhealth.ui.common.encodePreferredSports
import org.junit.Test

/**
 * POLISH-11: a fresh [OnboardingDraft] must default to a sane, non-zero training week (Run 2 /
 * Strength 2 / Soccer 1) rather than to all-zero caps, which would leave the suggester unable to
 * propose anything but cross-training/mobility (see `Constraints` C10).
 */
class OnboardingDraftTest {

    @Test
    fun default_draft_has_the_starter_sessions_per_week() {
        val draft = OnboardingDraft()

        assertThat(draft.sessionsPerWeek).containsExactly(
            SportGroup.RUN, 2,
            SportGroup.STRENGTH, 2,
            SportGroup.SOCCER, 1,
            // P12.3: cycling is offered but off by default — the cap is the suggester's bike gate.
            SportGroup.CYCLE, 0,
        )
    }

    @Test
    fun default_sessions_per_week_encodes_to_non_zero_caps() {
        val encoded = encodePreferredSports(OnboardingDraft().sessionsPerWeek)

        assertThat(encoded).contains("\"RUN\":2")
        assertThat(encoded).contains("\"STRENGTH\":2")
        assertThat(encoded).contains("\"SOCCER\":1")
    }

    @Test
    fun a_user_can_still_choose_all_zero_explicitly() {
        val cleared = OnboardingDraft().copy(sessionsPerWeek = SportGroup.entries.associateWith { 0 })

        assertThat(cleared.sessionsPerWeek.values).containsExactly(0, 0, 0, 0, 0, 0, 0)
    }
}
