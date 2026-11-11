package com.myhealth.domain.engine.calendar

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.CalendarClusterRule
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.CalendarImportConfig
import com.myhealth.domain.model.DeviceCalendarEntry
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.SportType
import kotlinx.datetime.TimeZone
import org.junit.Test

class CalendarImportPlannerTest {

    private val zone = TimeZone.UTC
    private val today = 20_000L
    private val todayStart = today * DAY

    private fun entry(title: String, dayOffset: Int, minutes: Int = 90, allDay: Boolean = false, location: String? = null) =
        DeviceCalendarEntry(
            calendarId = 1,
            title = title,
            startMillis = todayStart + dayOffset * DAY + if (allDay) 0 else 18 * HOUR + 30 * MINUTE,
            endMillis = todayStart + dayOffset * DAY + if (allDay) DAY else 18 * HOUR + 30 * MINUTE + minutes * MINUTE,
            allDay = allDay,
            location = location,
        )

    private val practice = listOf(entry("Practice", -7), entry("Practice", 0), entry("Practice", 7, location = "Pitch ,\n, ,"))
    private val calendar get() = ClusteredCalendar("Team", CalendarClusterer.cluster(practice))
    private val practiceKey get() = CalendarImportConfig.ruleKey("Team", calendar.clusters.single().key)
    private val on = CalendarClusterRule(enabled = true, type = EventType.SOCCER_TRAINING)

    private fun plan(
        calendars: List<ClusteredCalendar> = listOf(calendar),
        rules: Map<String, CalendarClusterRule> = mapOf(practiceKey to on),
        existing: List<CalendarEvent> = emptyList(),
        imported: Set<String> = emptySet(),
        unavailable: Set<String> = emptySet(),
    ) = CalendarImportPlanner.plan(calendars, rules, existing, imported, unavailable, todayStart, today, zone)

    /** The events a plan would leave in the database, with ids as Room would hand them out. */
    private fun stored(plan: CalendarImportPlan): List<CalendarEvent> =
        plan.upserts.mapIndexed { index, event -> event.copy(id = index + 1L, createdAtMillis = 1, updatedAtMillis = 1) }

    @Test
    fun upcoming_entries_become_events_and_past_ones_do_not() {
        val result = plan()

        assertThat(result.added).isEqualTo(2)
        assertThat(result.upserts.map { it.startDay }).containsExactly(today, today + 7)
        val event = result.upserts.last()
        assertThat(event.type).isEqualTo(EventType.SOCCER_TRAINING)
        assertThat(event.sportType).isEqualTo(SportType.SOCCER_TRAINING)
        assertThat(event.startMinuteOfDay).isEqualTo(18 * 60 + 30)
        assertThat(event.durationMin).isEqualTo(90)
        assertThat(event.location).isEqualTo("Pitch")
        assertThat(event.externalSource).isEqualTo("calendar:Team")
        assertThat(result.active).isEqualTo(2)
    }

    @Test
    fun a_second_run_changes_nothing() {
        val first = plan()

        val second = plan(existing = stored(first), imported = first.imported)

        assertThat(second.isNoop).isTrue()
        assertThat(second.imported).isEqualTo(first.imported)
    }

    @Test
    fun a_group_that_is_switched_off_or_has_no_rule_is_not_imported() {
        assertThat(plan(rules = mapOf(practiceKey to on.copy(enabled = false))).isNoop).isTrue()
        assertThat(plan(rules = emptyMap()).isNoop).isTrue()
    }

    @Test
    fun the_rule_sets_type_duration_and_key_event_and_a_rule_change_updates_in_place() {
        val first = plan()
        val changed = on.copy(type = EventType.SOCCER_MATCH, durationMin = 120, isKeyEvent = true)

        val second = plan(rules = mapOf(practiceKey to changed), existing = stored(first), imported = first.imported)

        assertThat(second.added).isEqualTo(0)
        assertThat(second.updated).isEqualTo(2)
        assertThat(second.deleteIds).isEmpty()
        assertThat(second.upserts.map { it.id }).containsExactly(1L, 2L)
        second.upserts.forEach {
            assertThat(it.type).isEqualTo(EventType.SOCCER_MATCH)
            assertThat(it.durationMin).isEqualTo(120)
            assertThat(it.isKeyEvent).isTrue()
        }
    }

    @Test
    fun a_cancelled_entry_is_removed_but_not_when_an_activity_is_linked_or_it_lies_in_the_past() {
        val first = plan()
        val events = stored(first)
        val past = events[0].copy(id = 9, startDay = today - 7, externalId = "old|practice")
        val onlyToday = ClusteredCalendar("Team", CalendarClusterer.cluster(practice.take(2)))

        val second = plan(calendars = listOf(onlyToday), existing = events + past, imported = first.imported)
        assertThat(second.deleteIds).containsExactly(2L)

        val linked = plan(
            calendars = listOf(onlyToday),
            existing = listOf(events[0], events[1].copy(linkedActivityId = 5)),
            imported = first.imported,
        )
        assertThat(linked.deleteIds).isEmpty()
    }

    @Test
    fun an_event_deleted_in_the_app_is_not_written_again_until_it_is_brought_back() {
        val first = plan()
        val kept = stored(first).take(1)

        val second = plan(existing = kept, imported = first.imported)
        assertThat(second.isNoop).isTrue()
        assertThat(second.removedInApp).isEqualTo(1)
        assertThat(second.active).isEqualTo(1)

        val restored = plan(existing = kept, imported = emptySet())
        assertThat(restored.added).isEqualTo(1)
    }

    @Test
    fun a_calendar_that_is_no_longer_chosen_loses_its_upcoming_events_a_missing_one_keeps_them() {
        val first = plan()
        val events = stored(first)

        val deselected = plan(calendars = emptyList(), existing = events, imported = first.imported)
        assertThat(deselected.deleteIds).containsExactly(1L, 2L)
        assertThat(deselected.imported).isEmpty()

        val missing = plan(
            calendars = emptyList(),
            existing = events,
            imported = first.imported,
            unavailable = setOf("calendar:Team"),
        )
        assertThat(missing.isNoop).isTrue()
        assertThat(missing.imported).isEqualTo(first.imported)
    }

    @Test
    fun events_made_in_the_app_are_never_touched() {
        val own = stored(plan()).first().copy(id = 7, externalSource = null, externalId = null)

        val result = plan(calendars = emptyList(), existing = listOf(own))

        assertThat(result.isNoop).isTrue()
    }

    @Test
    fun an_all_day_entry_has_no_time_and_no_duration() {
        val days = listOf(entry("Tournament", 3, allDay = true), entry("Tournament", 10, allDay = true))
        val cal = ClusteredCalendar("Team", CalendarClusterer.cluster(days))
        val key = CalendarImportConfig.ruleKey("Team", cal.clusters.single().key)

        val result = CalendarImportPlanner.plan(
            listOf(cal), mapOf(key to on.copy(durationMin = 60)), emptyList(), emptySet(), emptySet(),
            todayStart, today, TimeZone.of("Europe/Berlin"),
        )

        assertThat(result.upserts.map { it.startDay }).containsExactly(today + 3, today + 10)
        result.upserts.forEach {
            assertThat(it.startMinuteOfDay).isNull()
            assertThat(it.durationMin).isNull()
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
