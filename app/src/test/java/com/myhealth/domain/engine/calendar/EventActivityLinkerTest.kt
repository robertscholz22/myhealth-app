package com.myhealth.domain.engine.calendar

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.SportType
import com.myhealth.testutil.Fixtures
import org.junit.Test
import kotlinx.datetime.LocalDate

/**
 * The named cases of PLAN P3.3. The day is Tuesday 2026-09-15 and the zone is UTC, so an
 * activity's `startAtMillis` maps to the minute-of-day printed in its ISO string.
 */
class EventActivityLinkerTest {

    private val day = "2026-09-15"

    @Test
    fun link01_exact_overlap_same_sport_auto_links() {
        val occurrence = occurrence(startMinuteOfDay = 18 * 60, durationMin = 90)
        val activity = CalendarFixtures.activity(
            id = 7,
            startIso = "${day}T18:00:00Z",
            durationMin = 90,
        )

        val proposals = EventActivityLinker.propose(listOf(occurrence), listOf(activity), Fixtures.TZ)

        assertThat(proposals).hasSize(1)
        assertThat(proposals.single().confidence).isWithin(1e-9).of(1.0)
        assertThat(proposals.single().autoApply).isTrue()
        assertThat(proposals.single().activity.id).isEqualTo(7)
    }

    @Test
    fun link02_ambiguous_two_candidates_only_proposes() {
        val occurrence = occurrence(startMinuteOfDay = 18 * 60, durationMin = 90)
        val early = CalendarFixtures.activity(id = 1, startIso = "${day}T17:40:00Z", durationMin = 90)
        val late = CalendarFixtures.activity(id = 2, startIso = "${day}T18:20:00Z", durationMin = 90)

        val proposals = EventActivityLinker.propose(listOf(occurrence), listOf(early, late), Fixtures.TZ)

        assertThat(proposals).hasSize(2)
        // Symmetric candidates tie above the auto threshold — a tie is never the unique best.
        assertThat(proposals.map { it.confidence }.distinct()).hasSize(1)
        assertThat(proposals.first().confidence).isAtLeast(EventActivityLinker.AUTO_APPLY_THRESHOLD)
        assertThat(proposals.none { it.autoApply }).isTrue()
    }

    @Test
    fun link03_wrong_sport_group_not_linked() {
        val occurrence = occurrence(startMinuteOfDay = 18 * 60, durationMin = 90)
        val run = CalendarFixtures.activity(
            id = 3,
            startIso = "${day}T19:00:00Z",
            durationMin = 60,
            sportType = SportType.RUN_OUTDOOR,
        )

        assertThat(EventActivityLinker.confidence(occurrence, run, Fixtures.TZ))
            .isWithin(1e-9).of(0.316667)
        assertThat(EventActivityLinker.propose(listOf(occurrence), listOf(run), Fixtures.TZ)).isEmpty()

        // Even a perfectly overlapping wrong-sport activity can never be auto-linked (0.7 < 0.80).
        val simultaneous = CalendarFixtures.activity(
            id = 4,
            startIso = "${day}T18:00:00Z",
            durationMin = 90,
            sportType = SportType.RUN_OUTDOOR,
        )
        val proposals = EventActivityLinker.propose(listOf(occurrence), listOf(simultaneous), Fixtures.TZ)
        assertThat(proposals.single().confidence).isWithin(1e-9).of(0.7)
        assertThat(proposals.single().autoApply).isFalse()
    }

    @Test
    fun link04_all_day_event_links_by_day() {
        val allDay = occurrence(
            startMinuteOfDay = null,
            durationMin = null,
            type = EventType.RACE,
            sportType = SportType.RUN_OUTDOOR,
        )
        val sameDay = CalendarFixtures.activity(
            id = 5,
            startIso = "${day}T06:12:00Z",
            durationMin = 105,
            sportType = SportType.RUN_OUTDOOR,
        )
        val otherDay = CalendarFixtures.activity(
            id = 6,
            startIso = "2026-09-16T06:12:00Z",
            durationMin = 105,
            sportType = SportType.RUN_OUTDOOR,
        )

        val proposals = EventActivityLinker.propose(
            listOf(allDay),
            listOf(sameDay, otherDay),
            Fixtures.TZ,
        )

        // overlapRatio = 1.0, sportScore = 1.0, startScore = 0.5 -> 0.5 + 0.3 + 0.1
        assertThat(proposals).hasSize(1)
        assertThat(proposals.single().activity.id).isEqualTo(5)
        assertThat(proposals.single().confidence).isWithin(1e-9).of(0.9)
        assertThat(proposals.single().autoApply).isTrue()
    }

    @Test
    fun link05_already_linked_activity_skipped() {
        val linked = occurrence(startMinuteOfDay = 18 * 60, durationMin = 90, eventId = 1, linkedActivityId = 9)
        val open = occurrence(startMinuteOfDay = 18 * 60, durationMin = 90, eventId = 2)
        val activity = CalendarFixtures.activity(id = 9, startIso = "${day}T18:00:00Z", durationMin = 90)

        val proposals = EventActivityLinker.propose(
            listOf(linked, open),
            listOf(activity),
            Fixtures.TZ,
        )

        assertThat(proposals).isEmpty()
    }

    @Test
    fun link06_confidence_threshold_boundaries() {
        // overlapRatio 0.5, sportScore 1.0, startScore 0.0 -> exactly the propose threshold.
        val twoHourEvent = occurrence(startMinuteOfDay = 9 * 60, durationMin = 120)
        val onThreshold = CalendarFixtures.activity(id = 11, startIso = "${day}T10:30:00Z", durationMin = 60)
        assertThat(EventActivityLinker.confidence(twoHourEvent, onThreshold, Fixtures.TZ))
            .isEqualTo(EventActivityLinker.PROPOSE_THRESHOLD)
        assertThat(propose(twoHourEvent, onThreshold)).hasSize(1)

        val justBelow = CalendarFixtures.activity(id = 12, startIso = "${day}T10:31:00Z", durationMin = 60)
        assertThat(EventActivityLinker.confidence(twoHourEvent, justBelow, Fixtures.TZ))
            .isLessThan(EventActivityLinker.PROPOSE_THRESHOLD)
        assertThat(propose(twoHourEvent, justBelow)).isEmpty()

        // overlapRatio 1.0, sportScore 1.0, startScore 0.0 -> exactly the auto-apply threshold.
        val longEvent = occurrence(startMinuteOfDay = 10 * 60, durationMin = 240)
        val onAuto = CalendarFixtures.activity(id = 13, startIso = "${day}T11:30:00Z", durationMin = 60)
        assertThat(EventActivityLinker.confidence(longEvent, onAuto, Fixtures.TZ))
            .isEqualTo(EventActivityLinker.AUTO_APPLY_THRESHOLD)
        assertThat(propose(longEvent, onAuto).single().autoApply).isTrue()

        // Same timing but only the sport group matches: 0.5 + 0.24 -> proposed, never auto-applied.
        val sameGroup = CalendarFixtures.activity(
            id = 14,
            startIso = "${day}T11:30:00Z",
            durationMin = 60,
            sportType = SportType.SOCCER_MATCH,
        )
        assertThat(EventActivityLinker.confidence(longEvent, sameGroup, Fixtures.TZ))
            .isWithin(1e-9).of(0.74)
        assertThat(propose(longEvent, sameGroup).single().autoApply).isFalse()
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun propose(occurrence: EventOccurrence, activity: ActivitySummary) =
        EventActivityLinker.propose(listOf(occurrence), listOf(activity), Fixtures.TZ)

    private fun occurrence(
        startMinuteOfDay: Int?,
        durationMin: Int?,
        eventId: Long = 1L,
        type: EventType = EventType.SOCCER_TRAINING,
        sportType: SportType? = SportType.SOCCER_TRAINING,
        linkedActivityId: Long? = null,
    ): EventOccurrence {
        val event: CalendarEvent = CalendarFixtures.event(
            id = eventId,
            startIso = day,
            type = type,
            startMinuteOfDay = startMinuteOfDay,
            durationMin = durationMin,
            sportType = sportType,
            linkedActivityId = linkedActivityId,
        )
        return RecurrenceExpander.expand(
            event,
            emptyList(),
            LocalDate.parse(day),
            LocalDate.parse(day),
        ).single()
    }
}
