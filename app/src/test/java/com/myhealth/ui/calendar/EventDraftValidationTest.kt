package com.myhealth.ui.calendar

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.EventType
import com.myhealth.domain.util.minusDays
import org.junit.Test
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** Unit tests for the pure `validate(draft)` function (PLAN P3.6). */
class EventDraftValidationTest {

    private val validDraft = EventDraft(
        type = EventType.APPOINTMENT,
        title = "Physio",
        date = LocalDate(2026, 9, 14),
    )

    @Test
    fun valid_draft_has_no_errors() {
        val errors = validate(validDraft)

        assertThat(errors).isEmpty()
    }

    @Test
    fun empty_title_is_invalid() {
        val errors = validate(validDraft.copy(title = "   "))

        assertThat(errors).containsKey(EventField.TITLE)
    }

    @Test
    fun duration_at_or_below_zero_is_invalid() {
        val errors = validate(validDraft.copy(durationMin = 0))

        assertThat(errors).containsKey(EventField.DURATION)
        assertThat(validate(validDraft.copy(durationMin = -5))).containsKey(EventField.DURATION)
        assertThat(validate(validDraft.copy(durationMin = 45))).doesNotContainKey(EventField.DURATION)
    }

    @Test
    fun recurrence_until_before_the_start_date_is_invalid() {
        val draft = validDraft.copy(
            recurrenceMode = RecurrenceMode.WEEKLY,
            recurrenceWeekdays = setOf(DayOfWeek.MONDAY),
            recurrenceUntil = validDraft.date!!.minusDays(1),
        )

        val errors = validate(draft)

        assertThat(errors).containsKey(EventField.RECURRENCE_UNTIL)
    }

    @Test
    fun race_without_a_target_distance_is_invalid() {
        val errors = validate(validDraft.copy(type = EventType.RACE, targetDistanceKm = null))

        assertThat(errors).containsKey(EventField.DISTANCE)
        assertThat(validate(validDraft.copy(type = EventType.RACE, targetDistanceKm = 10.0)))
            .doesNotContainKey(EventField.DISTANCE)
    }

    @Test
    fun weekly_recurrence_with_no_weekday_picked_is_invalid() {
        val errors = validate(validDraft.copy(recurrenceMode = RecurrenceMode.WEEKLY, recurrenceWeekdays = emptySet()))

        assertThat(errors).containsKey(EventField.RECURRENCE_WEEKDAYS)
    }
}
