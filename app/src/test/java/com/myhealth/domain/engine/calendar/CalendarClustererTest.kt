package com.myhealth.domain.engine.calendar

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.DeviceCalendarEntry
import com.myhealth.domain.model.EventType
import org.junit.Test

class CalendarClustererTest {

    private fun entry(title: String, day: Int, minutes: Int = 90) = DeviceCalendarEntry(
        calendarId = 1,
        title = title,
        startMillis = day * DAY + 18 * HOUR,
        endMillis = day * DAY + 18 * HOUR + minutes * 60_000L,
        allDay = false,
        location = null,
    )

    private val season = listOf(
        entry("Training in Northfield ,", 1),
        entry("FC Example (Over 35) - SV Alpha", 3),
        entry("Training in Northfield ,", 8),
        entry("SV Beta - FC Example (Over 35)", 10),
        entry("Training in Northfield ,", 15),
        entry("SG Gamma - FC Example (Over 35)", 17),
        entry("Christmas party", 30),
    )

    @Test
    fun a_team_calendar_splits_into_matches_practice_and_the_rest() {
        val clusters = CalendarClusterer.cluster(season)

        assertThat(clusters.map { it.kind })
            .containsExactly(ClusterKind.FIXTURES, ClusterKind.REPEATED, ClusterKind.OTHER).inOrder()
        val (matches, practice, other) = clusters
        assertThat(matches.name).isEqualTo("FC Example (Over 35)")
        assertThat(matches.entries).hasSize(3)
        assertThat(practice.name).isEqualTo("Training in Northfield")
        assertThat(practice.entries).hasSize(3)
        assertThat(other.entries.map { it.title }).containsExactly("Christmas party")
    }

    @Test
    fun the_same_appointment_written_twice_counts_once() {
        val clusters = CalendarClusterer.cluster(season + season)

        assertThat(clusters.sumOf { it.entries.size }).isEqualTo(season.size)
    }

    @Test
    fun one_pairing_alone_is_not_a_fixture_list() {
        val clusters = CalendarClusterer.cluster(listOf(entry("Dinner - Anna", 1), entry("Dentist", 2)))

        assertThat(clusters.map { it.kind }).containsExactly(ClusterKind.OTHER)
    }

    @Test
    fun a_group_with_a_rule_survives_a_week_with_a_single_entry() {
        val full = CalendarClusterer.cluster(season)
        val known = full.map { it.key }.toSet()

        val thin = CalendarClusterer.cluster(
            listOf(entry("Training in Northfield ,", 22), entry("FC Example (Over 35) - SV Delta", 24)),
            known,
        )

        assertThat(thin.map { it.key }).containsExactly(full[0].key, full[1].key)
        assertThat(CalendarClusterer.cluster(listOf(entry("Training in Northfield ,", 22))).single().kind)
            .isEqualTo(ClusterKind.OTHER)
    }

    @Test
    fun two_teams_in_one_calendar_get_a_group_each() {
        val clusters = CalendarClusterer.cluster(
            listOf(
                entry("Team One - A", 1), entry("B - Team One", 2), entry("Team One - C", 3),
                entry("Team Two vs D", 4), entry("E vs Team Two", 5),
            ),
        )

        assertThat(clusters.map { it.name }).containsExactly("Team One", "Team Two").inOrder()
    }

    @Test
    fun suggestions_follow_what_the_group_looks_like() {
        val (matches, practice, other) = CalendarClusterer.cluster(season)

        assertThat(CalendarClusterer.suggestedRule(matches).let { it.enabled to it.type })
            .isEqualTo(true to EventType.SOCCER_MATCH)
        assertThat(CalendarClusterer.suggestedRule(practice).let { it.enabled to it.type })
            .isEqualTo(true to EventType.SOCCER_TRAINING)
        assertThat(CalendarClusterer.suggestedRule(other).enabled).isFalse()
        val birthdays = CalendarClusterer.cluster(listOf(entry("Birthday", 1), entry("Birthday", 2))).single()
        assertThat(CalendarClusterer.suggestedRule(birthdays).let { it.enabled to it.type })
            .isEqualTo(false to EventType.APPOINTMENT)
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
