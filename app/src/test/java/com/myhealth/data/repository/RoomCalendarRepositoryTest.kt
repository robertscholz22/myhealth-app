package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.dao.LoadDao
import com.myhealth.data.db.dao.MealDao
import com.myhealth.data.db.dao.NutritionDao
import com.myhealth.data.db.dao.PlanDao
import com.myhealth.data.db.dao.SleepDao
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.engine.activity.ActivityFields
import com.myhealth.domain.engine.activity.ActivityFixtures
import com.myhealth.domain.engine.calendar.CalendarFixtures
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [RoomCalendarRepository] over in-memory DAOs (PLAN P3.2/P3.3): the event ⇄ activity link, the
 * `SOCCER_MATCH` sport upgrade of §2.4/P3.7, and the link proposals P3.7's UI consumes. The
 * aggregate itself is covered by [CalendarAggregatorTest], so the unused DAOs are relaxed mocks.
 */
class RoomCalendarRepositoryTest {

    private val eventDao = FakeEventDao()
    private val activityDao = FakeActivityDao()
    private val clock = Fixtures.fixedClock("2026-09-15T21:00:00Z")
    private val activityRepo: ActivityRepository =
        RoomActivityRepository(activityDao, ActivityIngestor(activityDao, DirectTransactionRunner, clock), clock, Dispatchers.Unconfined)

    /** POLISH-8: counts the `onPlanChanged` hook AppGraph wires to `markProposedStale`. */
    private var planChangedCount = 0

    private val repo = RoomCalendarRepository(
        eventDao = eventDao,
        planDao = mockk<PlanDao>(relaxed = true),
        activityDao = activityDao,
        mealDao = mockk<MealDao>(relaxed = true),
        nutritionDao = mockk<NutritionDao>(relaxed = true),
        loadDao = mockk<LoadDao>(relaxed = true),
        sleepDao = mockk<SleepDao>(relaxed = true),
        activityRepo = activityRepo,
        clock = clock,
        onPlanChanged = { planChangedCount++ },
        zone = Fixtures.TZ,
        ioDispatcher = Dispatchers.Unconfined,
        computeDispatcher = Dispatchers.Unconfined,
    )

    private val day = "2026-09-15"

    @Test
    fun linking_a_soccer_match_upgrades_the_activity_sport_and_pins_the_field() = runTest {
        val eventId = repo.upsertEvent(
            CalendarFixtures.event(
                id = 0,
                startIso = day,
                type = EventType.SOCCER_MATCH,
                title = "Match day",
                sportType = SportType.SOCCER_MATCH,
            ),
        ).value()
        val activityId = storeActivity(SportType.HIIT)

        val result = repo.linkActivity(eventId, activityId, LinkMethod.AUTO_ACCEPTED)

        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        assertThat(repo.getEvent(eventId)?.linkedActivityId).isEqualTo(activityId)
        assertThat(repo.getEvent(eventId)?.linkMethod).isEqualTo(LinkMethod.AUTO_ACCEPTED)
        val activity = activityDao.getById(activityId)!!
        assertThat(activity.sportType).isEqualTo(SportType.SOCCER_MATCH)
        assertThat(activity.sportGroup).isEqualTo(SportGroup.SOCCER)
        assertThat(activity.userEditedFieldsCsv).contains(ActivityFields.SPORT_TYPE)
        assertThat(activity.dedupeBucket).startsWith("SOCCER|")
    }

    @Test
    fun linking_any_other_event_type_leaves_the_activity_untouched() = runTest {
        val eventId = repo.upsertEvent(
            CalendarFixtures.event(id = 0, startIso = day, type = EventType.SOCCER_TRAINING),
        ).value()
        val activityId = storeActivity(SportType.HIIT)

        repo.linkActivity(eventId, activityId, LinkMethod.MANUAL)

        val activity = activityDao.getById(activityId)!!
        assertThat(activity.sportType).isEqualTo(SportType.HIIT)
        assertThat(activity.userEditedFieldsCsv).isEmpty()
    }

    @Test
    fun every_event_write_notifies_the_plan_changed_hook() = runTest {
        // POLISH-8: an insert, an update and a delete each make an open PROPOSED batch stale.
        val id = repo.upsertEvent(CalendarFixtures.event(id = 0, startIso = day)).value()
        assertThat(planChangedCount).isEqualTo(1)

        repo.upsertEvent(repo.getEvent(id)!!.copy(title = "Moved"))
        assertThat(planChangedCount).isEqualTo(2)

        repo.deleteEvent(id)
        assertThat(planChangedCount).isEqualTo(3)
    }

    @Test
    fun linking_an_unknown_event_is_a_validation_error() = runTest {
        assertThat(repo.linkActivity(404L, 1L, LinkMethod.MANUAL)).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun upserting_an_event_stamps_timestamps_and_the_until_day() = runTest {
        val id = repo.upsertEvent(
            CalendarFixtures.event(
                id = 0,
                startIso = day,
                recurrenceRule = "FREQ=WEEKLY;BYDAY=TU;UNTIL=20261027",
            ),
        ).value()

        val stored = repo.getEvent(id)!!
        assertThat(stored.recurrenceUntilDay).isEqualTo(Fixtures.epochDay("2026-10-27"))
        assertThat(stored.updatedAtMillis).isEqualTo(clock.millis())
        assertThat(repo.observeOccurrences(Fixtures.epochDay(day), Fixtures.epochDay("2026-09-29")).first())
            .hasSize(3)
    }

    @Test
    fun link_proposals_cover_unlinked_events_and_disappear_once_linked() = runTest {
        val eventId = repo.upsertEvent(
            CalendarFixtures.event(
                id = 0,
                startIso = day,
                startMinuteOfDay = 18 * 60,
                durationMin = 90,
                sportType = SportType.SOCCER_TRAINING,
            ),
        ).value()
        val activityId = storeActivity(SportType.SOCCER_TRAINING, startIso = "${day}T18:00:00Z")
        val from = Fixtures.epochDay(day)

        val proposals = repo.observeLinkProposals(from, from).first()

        assertThat(proposals).hasSize(1)
        assertThat(proposals.single().activity.id).isEqualTo(activityId)
        assertThat(proposals.single().eventOccurrence.eventId).isEqualTo(eventId)
        assertThat(proposals.single().autoApply).isTrue()

        repo.linkActivity(eventId, activityId, LinkMethod.AUTO_ACCEPTED)
        assertThat(repo.observeLinkProposals(from, from).first()).isEmpty()
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun Outcome<Long>.value(): Long = (this as Outcome.Ok<Long>).value

    private suspend fun storeActivity(
        sportType: SportType,
        startIso: String = "2026-09-15T18:00:00Z",
    ): Long = activityDao.upsert(
        ActivityFixtures.session(
            source = ActivitySource.HEALTH_CONNECT,
            startIso = startIso,
            durationSec = 90 * 60,
            sportType = sportType,
            distanceMeters = null,
        ).toEntity(),
    )
}
