package com.myhealth.ui.calendar

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.calendar.LinkProposal
import org.junit.Test

/** Unit tests for the pure candidate-ordering + confidence-label helpers of [LinkActivitySheet] (P3.7). */
class LinkSheetStateTest {

    @Test
    fun proposals_are_ordered_before_the_manual_fallback_without_duplicates() {
        val occurrence = CalendarUiFixtures.occurrence(id = 1)
        val activityA = CalendarUiFixtures.activity(id = 10)
        val activityB = CalendarUiFixtures.activity(id = 11)
        val activityC = CalendarUiFixtures.activity(id = 12)
        val proposals = listOf(
            LinkProposal(occurrence, activityB, confidence = 0.6, autoApply = false),
            LinkProposal(occurrence, activityA, confidence = 0.9, autoApply = false),
        )

        val candidates = linkCandidatesFor(occurrence, proposals, listOf(activityA, activityB, activityC))

        assertThat(candidates.map { it.activityId }).containsExactly(10L, 11L, 12L).inOrder()
        assertThat(candidates[0].confidence).isEqualTo(0.9)
        assertThat(candidates[1].confidence).isEqualTo(0.6)
        assertThat(candidates[2].confidence).isNull()
    }

    @Test
    fun confidence_label_rounds_a_fraction_to_a_percentage() {
        assertThat(confidenceLabel(0.8234)).isEqualTo("82%")
        assertThat(confidenceLabel(0.5)).isEqualTo("50%")
    }
}
