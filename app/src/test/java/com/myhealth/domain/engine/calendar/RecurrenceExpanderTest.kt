package com.myhealth.domain.engine.calendar

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.myhealth.testutil.Fixtures
import org.junit.Test
import kotlinx.datetime.LocalDate

/**
 * The named cases of PLAN P3.1. All arithmetic is on `LocalDate`, so the tests pin exact days —
 * Tuesday 2026-09-15 (ISO week 38) is the anchor of the weekly series.
 */
class RecurrenceExpanderTest {

    @Test
    fun rec01_single_event_in_range() {
        val event = CalendarFixtures.event(
            startIso = "2026-09-15",
            title = "Team training",
            startMinuteOfDay = 18 * 60,
            durationMin = 90,
        )

        val occurrences = expand(event, from = "2026-09-14", to = "2026-09-20")

        assertThat(occurrences).hasSize(1)
        val only = occurrences.single()
        assertThat(only.occurrenceDay).isEqualTo(Fixtures.epochDay("2026-09-15"))
        assertThat(only.effectiveTitle).isEqualTo("Team training")
        assertThat(only.effectiveStartMinuteOfDay).isEqualTo(1080)
        assertThat(only.effectiveDurationMin).isEqualTo(90)
        assertThat(only.isOverride).isFalse()
        assertThat(only.type).isEqualTo(EventType.SOCCER_TRAINING)
    }

    @Test
    fun rec02_weekly_two_weekdays() {
        val event = CalendarFixtures.event(
            startIso = "2026-09-15",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TU,TH",
        )

        val occurrences = expand(event, from = "2026-09-14", to = "2026-09-27")

        assertThat(occurrences.days()).containsExactly(
            "2026-09-15", "2026-09-17", "2026-09-22", "2026-09-24",
        ).inOrder()
    }

    @Test
    fun rec03_interval_two_weeks() {
        val event = CalendarFixtures.event(
            startIso = "2026-09-15",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TU;INTERVAL=2",
        )

        assertThat(expand(event, from = "2026-09-14", to = "2026-10-20").days())
            .containsExactly("2026-09-15", "2026-09-29", "2026-10-13").inOrder()

        // INTERVAL counts weeks from the event's own ISO week, not from the queried window.
        assertThat(expand(event, from = "2026-09-22", to = "2026-10-20").days())
            .containsExactly("2026-09-29", "2026-10-13").inOrder()
    }

    @Test
    fun rec04_until_boundary_inclusive() {
        val event = CalendarFixtures.event(
            startIso = "2026-09-15",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TU;UNTIL=20260929",
        )

        assertThat(expand(event, from = "2026-09-01", to = "2026-10-31").days())
            .containsExactly("2026-09-15", "2026-09-22", "2026-09-29").inOrder()

        // The denormalized column is honoured the same way, and the earlier bound wins.
        val denormalized = CalendarFixtures.event(
            startIso = "2026-09-15",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TU",
            recurrenceUntilDay = Fixtures.epochDay("2026-09-22"),
        )
        assertThat(expand(denormalized, from = "2026-09-01", to = "2026-10-31").days())
            .containsExactly("2026-09-15", "2026-09-22").inOrder()
    }

    @Test
    fun rec05_count_limit() {
        val counted = CalendarFixtures.event(
            startIso = "2026-09-15",
            recurrenceRule = "FREQ=DAILY;COUNT=3",
        )

        assertThat(expand(counted, from = "2026-09-01", to = "2026-12-31").days())
            .containsExactly("2026-09-15", "2026-09-16", "2026-09-17").inOrder()

        // COUNT is counted from the series start, so a later window sees only the tail.
        assertThat(expand(counted, from = "2026-09-17", to = "2026-12-31").days())
            .containsExactly("2026-09-17")

        // Hard guard: never more than 1000 occurrences per call.
        val endless = CalendarFixtures.event(startIso = "2026-09-15", recurrenceRule = "FREQ=DAILY")
        assertThat(expand(endless, from = "2026-09-15", to = "2036-09-15"))
            .hasSize(RecurrenceExpander.MAX_OCCURRENCES)
    }

    @Test
    fun rec06_skip_override_removes_occurrence() {
        val event = CalendarFixtures.event(
            startIso = "2026-09-15",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TU",
        )
        val skip = CalendarFixtures.override(occurrenceIso = "2026-09-22", action = "SKIP")

        val occurrences = RecurrenceExpander.expand(
            event,
            listOf(skip),
            LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-10-04"),
        )

        assertThat(occurrences.days()).containsExactly("2026-09-15", "2026-09-29").inOrder()
    }

    @Test
    fun rec07_move_override_changes_day() {
        val event = CalendarFixtures.event(
            startIso = "2026-09-15",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TU",
        )
        val move = CalendarFixtures.override(
            occurrenceIso = "2026-09-22",
            action = "MOVE",
            newStartIso = "2026-09-23",
            newStartMinuteOfDay = 19 * 60 + 30,
        )

        val occurrences = RecurrenceExpander.expand(
            event,
            listOf(move),
            LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-28"),
        )

        assertThat(occurrences.days()).containsExactly("2026-09-15", "2026-09-23").inOrder()
        val moved = occurrences.single { it.occurrenceDay == Fixtures.epochDay("2026-09-23") }
        assertThat(moved.effectiveStartMinuteOfDay).isEqualTo(1170)
        assertThat(moved.effectiveDurationMin).isEqualTo(90)
        assertThat(moved.isOverride).isTrue()
    }

    @Test
    fun rec08_edit_override_changes_title() {
        val event = CalendarFixtures.event(
            startIso = "2026-09-15",
            title = "Team training",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TU",
        )
        val edit = CalendarFixtures.override(
            occurrenceIso = "2026-09-22",
            action = "EDIT",
            newTitle = "Friendly match",
            newDurationMin = 120,
        )

        val occurrences = RecurrenceExpander.expand(
            event,
            listOf(edit),
            LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-28"),
        )

        val edited = occurrences.single { it.occurrenceDay == Fixtures.epochDay("2026-09-22") }
        assertThat(edited.effectiveTitle).isEqualTo("Friendly match")
        assertThat(edited.effectiveDurationMin).isEqualTo(120)
        assertThat(edited.effectiveStartMinuteOfDay).isEqualTo(1080)
        assertThat(edited.isOverride).isTrue()
        assertThat(occurrences.first().effectiveTitle).isEqualTo("Team training")
    }

    @Test
    fun rec09_range_outside_series_returns_empty() {
        val single = CalendarFixtures.event(startIso = "2026-09-15")
        assertThat(expand(single, from = "2026-09-16", to = "2026-09-30")).isEmpty()

        val series = CalendarFixtures.event(
            startIso = "2026-09-15",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TU;UNTIL=20260929",
        )
        assertThat(expand(series, from = "2026-08-01", to = "2026-09-14")).isEmpty()
        assertThat(expand(series, from = "2026-10-01", to = "2026-10-31")).isEmpty()
        // A window that ends before it starts is empty, never an error.
        assertThat(expand(series, from = "2026-09-30", to = "2026-09-01")).isEmpty()
    }

    @Test
    fun rec10_dst_does_not_shift_local_day() {
        // Europe/Berlin switches to summer time in the night of Sunday 2026-03-29; 02:30 local
        // does not exist that day. Pure LocalDate arithmetic must not care.
        val event = CalendarFixtures.event(
            startIso = "2026-03-22",
            startMinuteOfDay = 150,
            durationMin = 60,
            recurrenceRule = "FREQ=WEEKLY;BYDAY=SU",
        )

        val occurrences = expand(event, from = "2026-03-22", to = "2026-04-05")

        assertThat(occurrences.days())
            .containsExactly("2026-03-22", "2026-03-29", "2026-04-05").inOrder()
        assertThat(occurrences.map { it.effectiveStartMinuteOfDay }).containsExactly(150, 150, 150)
        val days = occurrences.map { it.occurrenceDay }
        assertThat(days[1] - days[0]).isEqualTo(7)
        assertThat(days[2] - days[1]).isEqualTo(7)
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun expand(
        event: com.myhealth.domain.model.CalendarEvent,
        from: String,
        to: String,
    ): List<EventOccurrence> = RecurrenceExpander.expand(
        event,
        emptyList(),
        LocalDate.parse(from),
        LocalDate.parse(to),
    )

    private fun List<EventOccurrence>.days(): List<String> =
        map { LocalDate.fromEpochDays(it.occurrenceDay).toString() }
}
