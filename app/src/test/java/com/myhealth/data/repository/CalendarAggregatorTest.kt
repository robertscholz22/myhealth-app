package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.calendar.CalendarFixtures
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealLogSummary
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.ActivitySource
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * [CalendarAggregator] is a pure function over lists (PLAN P3.2), so these tests need no Room and
 * no dispatchers. Range: Monday 2026-09-14 … Sunday 2026-09-20.
 */
class CalendarAggregatorTest {

    private val from = Fixtures.epochDay("2026-09-14")
    private val to = Fixtures.epochDay("2026-09-20")
    private val tuesday = Fixtures.epochDay("2026-09-15")

    @Test
    fun empty_range_still_contains_every_day() {
        val days = CalendarAggregator.aggregate(fromDay = from, toDay = to)

        assertThat(days.keys).containsExactlyElementsIn((from..to).toList()).inOrder()
        assertThat(days.values.all { it.events.isEmpty() && it.planned.isEmpty() }).isTrue()
        assertThat(days.getValue(tuesday).intake).isEqualTo(MacroTotals.ZERO)
        assertThat(days.getValue(tuesday).target).isNull()
        assertThat(days.getValue(tuesday).load).isNull()
        assertThat(days.getValue(tuesday).sleep).isNull()

        // A window that ends before it starts yields nothing at all.
        assertThat(CalendarAggregator.aggregate(fromDay = to, toDay = from)).isEmpty()
    }

    @Test
    fun a_day_holding_every_kind_is_aggregated() {
        val days = CalendarAggregator.aggregate(
            fromDay = from,
            toDay = to,
            events = listOf(CalendarFixtures.event(id = 1, startIso = "2026-09-15")),
            planned = listOf(plannedSession(id = 2, day = tuesday)),
            activities = listOf(
                CalendarFixtures.activity(id = 3, startIso = "2026-09-15T18:05:00Z", durationMin = 88),
            ),
            meals = listOf(
                meal(id = 4, day = tuesday, atMinuteOfDay = 12 * 60, kcal = 640.0, proteinG = 35.0),
                meal(id = 5, day = tuesday, atMinuteOfDay = 20 * 60, kcal = 810.0, proteinG = 45.0),
            ),
            targets = listOf(target(tuesday, kcal = 2900)),
            loads = listOf(load(tuesday, trimp = 118.4)),
            sleep = listOf(sleep(tuesday, totalSleepMin = 447)),
        )

        val day = days.getValue(tuesday)
        assertThat(day.events.map { it.eventId }).containsExactly(1L)
        assertThat(day.planned.map { it.id }).containsExactly(2L)
        assertThat(day.activities.map { it.id }).containsExactly(3L)
        assertThat(day.meals.map { it.id }).containsExactly(4L, 5L).inOrder()
        assertThat(day.intake.kcal).isWithin(1e-9).of(1450.0)
        assertThat(day.intake.proteinG).isWithin(1e-9).of(80.0)
        assertThat(day.target?.kcal).isEqualTo(2900)
        assertThat(day.load?.trimp).isWithin(1e-9).of(118.4)
        assertThat(day.sleep?.totalSleepMin).isEqualTo(447)
        // Nothing leaks into the neighbouring days.
        assertThat(days.getValue(from).events).isEmpty()
        assertThat(days.getValue(to).meals).isEmpty()
    }

    @Test
    fun recurring_events_are_expanded_over_the_range() {
        val days = CalendarAggregator.aggregate(
            fromDay = from,
            toDay = to,
            events = listOf(
                CalendarFixtures.event(
                    id = 1,
                    startIso = "2026-09-15",
                    recurrenceRule = "FREQ=WEEKLY;BYDAY=TU,TH",
                ),
            ),
        )

        val withEvents = days.filterValues { it.events.isNotEmpty() }.keys
        assertThat(withEvents).containsExactly(
            Fixtures.epochDay("2026-09-15"),
            Fixtures.epochDay("2026-09-17"),
        ).inOrder()
    }

    @Test
    fun overrides_move_and_skip_occurrences_between_days() {
        val event = CalendarFixtures.event(
            id = 1,
            startIso = "2026-09-15",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TU,TH",
        )
        val days = CalendarAggregator.aggregate(
            fromDay = from,
            toDay = to,
            events = listOf(event),
            overrides = listOf(
                CalendarFixtures.override(id = 1, occurrenceIso = "2026-09-15", action = "SKIP"),
                CalendarFixtures.override(
                    id = 2,
                    occurrenceIso = "2026-09-17",
                    action = "MOVE",
                    newStartIso = "2026-09-18",
                    newTitle = "Moved training",
                ),
            ),
        )

        assertThat(days.getValue(Fixtures.epochDay("2026-09-15")).events).isEmpty()
        assertThat(days.getValue(Fixtures.epochDay("2026-09-17")).events).isEmpty()
        val moved = days.getValue(Fixtures.epochDay("2026-09-18")).events.single()
        assertThat(moved.effectiveTitle).isEqualTo("Moved training")
        assertThat(moved.isOverride).isTrue()
    }

    @Test
    fun entries_are_sorted_and_out_of_range_rows_are_dropped() {
        val days = CalendarAggregator.aggregate(
            fromDay = from,
            toDay = to,
            events = listOf(
                CalendarFixtures.event(id = 1, startIso = "2026-09-15", startMinuteOfDay = 19 * 60),
                CalendarFixtures.event(id = 2, startIso = "2026-09-15", startMinuteOfDay = null),
                CalendarFixtures.event(id = 3, startIso = "2026-09-15", startMinuteOfDay = 7 * 60),
                // Outside the window: expanded away, never rendered.
                CalendarFixtures.event(id = 4, startIso = "2026-10-06"),
            ),
            meals = listOf(
                meal(id = 9, day = tuesday, atMinuteOfDay = 20 * 60, kcal = 800.0, proteinG = 40.0),
                meal(id = 8, day = tuesday, atMinuteOfDay = 8 * 60, kcal = 400.0, proteinG = 20.0),
                meal(id = 7, day = Fixtures.epochDay("2026-10-06"), atMinuteOfDay = 8 * 60, kcal = 1.0, proteinG = 1.0),
            ),
        )

        // All-day entries sort first, then by start time.
        assertThat(days.getValue(tuesday).events.map { it.eventId }).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(days.getValue(tuesday).meals.map { it.id }).containsExactly(8L, 9L).inOrder()
        assertThat(days.values.sumOf { it.events.size }).isEqualTo(3)
        assertThat(days.values.sumOf { it.meals.size }).isEqualTo(2)
    }

    // ---- fixtures --------------------------------------------------------------------------

    private fun plannedSession(id: Long, day: Long) = PlannedSession(
        id = id,
        planId = null,
        day = day,
        startMinuteOfDay = 17 * 60,
        sportType = SportType.SOCCER_TRAINING,
        sessionType = SessionType.SOCCER_TRAINING,
        intensity = Intensity.MODERATE,
        targetDurationMin = 90,
        targetDistanceMeters = null,
        targetPaceSecPerKm = null,
        estimatedTrimp = 110.0,
        description = null,
        rationale = null,
        status = PlannedStatus.PLANNED,
        locked = false,
        linkedActivityId = null,
        sourceSuggestionId = null,
        createdAtMillis = CalendarFixtures.NOW,
        updatedAtMillis = CalendarFixtures.NOW,
    )

    private fun meal(id: Long, day: Long, atMinuteOfDay: Int, kcal: Double, proteinG: Double) =
        MealLogSummary(
            id = id,
            day = day,
            atMinuteOfDay = atMinuteOfDay,
            slot = MealSlot.LUNCH,
            name = null,
            totals = MacroTotals.ZERO.copy(kcal = kcal, proteinG = proteinG),
        )

    private fun target(day: Long, kcal: Int) = NutritionTarget(
        day = day,
        kcal = kcal,
        proteinG = 160,
        carbsG = 380,
        fatG = 85,
        fiberG = 35,
        sugarCapG = 70,
        satFatCapG = 28,
        saltG = 6.0,
        waterMl = 3000,
        bmrKcal = 1800,
        tdeeKcal = 2900,
        dayType = DayType.TRAINING,
        explanation = "",
        warnings = emptyList(),
        inputsHash = "h",
        computedAtMillis = CalendarFixtures.NOW,
    )

    private fun load(day: Long, trimp: Double) = DailyLoad(
        day = day,
        trimp = trimp,
        sessionCount = 1,
        atl = 90.0,
        ctl = 70.0,
        acwr = 1.1,
        tsb = -20.0,
        monotony = null,
        strain = null,
        recoveryScore = null,
        recoveryBand = null,
        recoveryConfidence = 0.5,
        flags = emptyList(),
        computedAtMillis = CalendarFixtures.NOW,
    )

    private fun sleep(night: Long, totalSleepMin: Int) = SleepRecord(
        id = 1,
        startAtMillis = CalendarFixtures.NOW,
        endAtMillis = CalendarFixtures.NOW + totalSleepMin * 60_000L,
        night = night,
        totalSleepMin = totalSleepMin,
        lightMin = null,
        deepMin = null,
        remMin = null,
        awakeMin = null,
        stages = null,
        source = ActivitySource.HEALTH_CONNECT,
        externalId = null,
        sleepScore = null,
    )
}
