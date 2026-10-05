package com.myhealth.ui.calendar

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.MacroTotals
import org.junit.Test

/** The pure target-vs-intake progress helper of P3.5. */
class DayDetailUiStateTest {

    @Test
    fun progress01_without_a_target_there_are_no_rows_and_no_fraction() {
        assertThat(targetProgressRows(null, CalendarUiFixtures.intake(1800.0))).isEmpty()
        assertThat(progressFraction(current = 1800.0, target = null)).isEqualTo(0f)
        assertThat(progressFraction(current = 1800.0, target = 0.0)).isEqualTo(0f)

        val state = DayDetailUiState(day = CalendarUiFixtures.DAY, isLoading = false)
        assertThat(state.isEmpty).isTrue()
    }

    @Test
    fun progress02_rows_carry_the_clamped_fraction_and_a_current_over_target_label() {
        val target = CalendarUiFixtures.target(kcal = 2000)
        val intake = MacroTotals(1500.0, 75.0, 110.0, 84.0, 0.0, 0.0, 0.0, 0.0)

        val rows = targetProgressRows(target, intake)

        assertThat(rows.map { it.label }).containsExactly("Energy", "Protein", "Carbs", "Fat").inOrder()
        assertThat(rows[0].valueLabel).isEqualTo("1500 / 2000 kcal")
        assertThat(rows[0].fraction).isWithin(1e-4f).of(0.75f)
        assertThat(rows[1].fraction).isWithin(1e-4f).of(0.5f)
        assertThat(rows[2].valueLabel).isEqualTo("110 / 220 g")
        // 84 g of a 70 g target is over: the bar is clamped to full, never past it.
        assertThat(rows[3].fraction).isEqualTo(1f)
        assertThat(formatSleepDuration(462)).isEqualTo("7h 42m")
    }
}
