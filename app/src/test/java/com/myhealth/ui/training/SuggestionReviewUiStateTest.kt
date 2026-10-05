package com.myhealth.ui.training

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.SuggestionBatch
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * [suggestionRows] — the review list's row derivation (PLAN §4.2 "Suggestion review", P6.7). The
 * point of the test is the rest days: a day the suggester deliberately left free (C3, §3.5.3) must
 * appear as its own row, not as a gap in the list.
 */
class SuggestionReviewUiStateTest {

    private val monday = Fixtures.epochDay("2026-09-14")

    @Test
    fun days_without_a_suggestion_become_rest_rows() {
        val batch = batch()
        val sessions = listOf(
            suggestion(id = 1L, day = monday, score = 0.7),
            // Two sessions on the same day: best score first, and the day is not a rest day.
            suggestion(id = 2L, day = monday + 2, score = 0.6),
            suggestion(id = 3L, day = monday + 2, score = 0.9),
            suggestion(id = 4L, day = monday + 5, score = 0.8),
            // A rejected suggestion is gone, so its day falls back to being a rest day.
            suggestion(id = 5L, day = monday + 6, score = 0.5, status = SuggestionStatus.REJECTED),
        )

        val rows = suggestionRows(batch, sessions)

        assertThat(rows).hasSize(8)
        assertThat(rows.map { it.day }).containsExactly(
            monday, monday + 1, monday + 2, monday + 2, monday + 3, monday + 4, monday + 5, monday + 6,
        ).inOrder()
        assertThat(rows.filter { it.isRest }.map { it.day })
            .containsExactly(monday + 1, monday + 3, monday + 4, monday + 6).inOrder()
        // Same-day ordering is score-descending, matching the engine's own placement order.
        assertThat(rows.filter { it.day == monday + 2 }.map { it.session?.id })
            .containsExactly(3L, 2L).inOrder()

        val state = SuggestionReviewUiState(isLoading = false, batch = batch, rows = rows)
        assertThat(state.restDayCount).isEqualTo(4)
        assertThat(state.sessions).hasSize(4)
        assertThat(state.totalSuggestedLoad).isWithin(1e-9).of(4 * 54.0)
        // Every card starts accepted; unticking one only moves it to the rejected list.
        assertThat(state.selectedIds).containsExactly(1L, 3L, 2L, 4L)
        val pruned = state.copy(accepted = mapOf(2L to false))
        assertThat(pruned.rejectedIds).containsExactly(2L)
        assertThat(pruned.selectedLoad).isWithin(1e-9).of(3 * 54.0)

        // No batch at all means no rows, not a week of empty rest days.
        assertThat(suggestionRows(null, sessions)).isEmpty()
    }

    private fun batch(): SuggestionBatch = SuggestionBatch(
        id = 1L,
        generatedAtMillis = 0L,
        horizonStartDay = monday,
        horizonEndDay = monday + 7,
        phase = TrainingPhase.BUILD,
        weeklyLoadTarget = 620.0,
        inputsHash = "hash",
        status = SuggestionStatus.PROPOSED,
    )

    private fun suggestion(
        id: Long,
        day: Long,
        score: Double,
        status: SuggestionStatus = SuggestionStatus.PROPOSED,
    ): SuggestedSession = SuggestedSession(
        id = id,
        batchId = 1L,
        day = day,
        sportType = SportType.RUN_OUTDOOR,
        sessionType = SessionType.EASY_RUN,
        intensity = Intensity.LOW,
        targetDurationMin = 45,
        targetDistanceMeters = null,
        estimatedTrimp = 54.0,
        score = score,
        rationale = emptyList(),
        status = status,
    )

    @Test
    fun header_explains_when_fixed_sessions_already_cover_the_target() {
        val state = SuggestionReviewUiState(isLoading = false, batch = batch(), fixedLoad = 700.0)
        val line = state.headerLine(
            targetLabel = "target", suggestedLabel = "suggested",
            restDaySingular = "rest day", restDayPlural = "rest days",
            replacesSingular = "will be replaced", replacesPlural = "will be replaced",
            fixedCoversFormat = "fixed %1\$d of %2\$d",
        )
        assertThat(line).contains("fixed 700 of ${Math.round(batch().weeklyLoadTarget)}")

        val below = state.copy(fixedLoad = 10.0).headerLine(
            targetLabel = "target", suggestedLabel = "suggested",
            restDaySingular = "rest day", restDayPlural = "rest days",
            replacesSingular = "will be replaced", replacesPlural = "will be replaced",
            fixedCoversFormat = "fixed %1\$d of %2\$d",
        )
        assertThat(below).doesNotContain("fixed ")
    }
}
