package com.myhealth.ui.goals

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.GoalType
import org.junit.Test
import kotlinx.datetime.LocalDate

/** The Goals screen's pure projection helpers (PLAN §4.2 "Goals", P6.1). */
class GoalsUiStateTest {

    private val today = LocalDate(2026, 9, 14)
    private val todayDay = today.toEpochDays()

    private fun rowsOf(vararg goals: com.myhealth.domain.model.Goal) =
        goalRows(goals.toList(), emptyList(), emptyList(), emptyList(), today)

    @Test
    fun every_goal_gets_a_progress_row() {
        val rows = rowsOf(
            SuggestFixtures.goal(id = 1L, type = GoalType.CONSISTENCY, targetValue = 4.0),
            SuggestFixtures.goal(id = 2L, type = GoalType.BODY_WEIGHT, targetWeightKg = 80.0),
        )
        assertThat(rows).hasSize(2)
        rows.forEach { assertThat(it.progress.statusText).isNotEmpty() }
    }

    @Test
    fun the_primary_goal_sorts_first_then_the_nearest_deadline() {
        val secondaryNear = SuggestFixtures.goal(id = 2L, priority = 2, targetDay = todayDay + 10)
        val secondaryFar = SuggestFixtures.goal(id = 3L, priority = 2, targetDay = todayDay + 90)
        val primary = SuggestFixtures.goal(id = 1L, priority = 1, targetDay = todayDay + 200)

        val sorted = rowsOf(secondaryFar, secondaryNear, primary).sortedForDisplay()
        assertThat(sorted.map { it.goal.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(sorted.first().isPrimary).isTrue()
    }

    @Test
    fun archived_goals_are_split_out_and_are_never_primary() {
        val active = SuggestFixtures.goal(id = 1L, priority = 1)
        val achieved = SuggestFixtures.goal(id = 2L, priority = 1, status = GoalStatus.ACHIEVED)
        val rows = rowsOf(active, achieved)

        assertThat(rows.activeOnly().map { it.goal.id }).containsExactly(1L)
        assertThat(rows.archivedOnly().map { it.goal.id }).containsExactly(2L)
        assertThat(rows.first { it.goal.id == 2L }.isPrimary).isFalse()
    }

    @Test
    fun an_empty_loaded_state_is_empty() {
        assertThat(GoalsUiState(isLoading = false).isEmpty).isTrue()
        assertThat(GoalsUiState(isLoading = true).isEmpty).isFalse()
        assertThat(GoalsUiState(isLoading = false, active = rowsOf(SuggestFixtures.goal())).isEmpty)
            .isFalse()
    }
}
