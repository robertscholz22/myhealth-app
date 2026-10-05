package com.myhealth.ui.calendar

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** Unit tests for `EventDraft.recurrenceRule()` (PLAN P3.6): the 3 named cases. */
class EventDraftRecurrenceTest {

    private val base = EventDraft(title = "Training", date = LocalDate(2026, 9, 14))

    @Test
    fun weekly_two_days_produces_a_rule_string() {
        val draft = base.copy(
            recurrenceMode = RecurrenceMode.WEEKLY,
            recurrenceWeekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
        )

        val rule = draft.recurrenceRule()

        assertThat(rule).isNotNull()
        assertThat(rule!!.format()).isEqualTo("FREQ=WEEKLY;BYDAY=TU,TH")
    }

    @Test
    fun every_two_weeks_with_until_produces_a_rule_string() {
        val draft = base.copy(
            recurrenceMode = RecurrenceMode.WEEKLY,
            recurrenceWeekdays = setOf(DayOfWeek.MONDAY),
            recurrenceIntervalWeeks = 2,
            recurrenceUntil = LocalDate(2026, 12, 31),
        )

        val rule = draft.recurrenceRule()

        assertThat(rule).isNotNull()
        assertThat(rule!!.interval).isEqualTo(2)
        assertThat(rule.untilDay).isEqualTo(LocalDate(2026, 12, 31).toEpochDays())
        assertThat(rule.format()).isEqualTo("FREQ=WEEKLY;BYDAY=MO;INTERVAL=2;UNTIL=20261231")
    }

    @Test
    fun none_recurrence_produces_a_null_rule() {
        val draft = base.copy(
            recurrenceMode = RecurrenceMode.NONE,
            recurrenceWeekdays = setOf(DayOfWeek.MONDAY),
            recurrenceIntervalWeeks = 3,
        )

        assertThat(draft.recurrenceRule()).isNull()
    }
}
