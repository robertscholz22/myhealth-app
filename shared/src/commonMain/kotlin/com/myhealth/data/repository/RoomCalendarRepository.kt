package com.myhealth.data.repository

import com.myhealth.data.db.dao.ActivityDao
import com.myhealth.data.db.dao.EventDao
import com.myhealth.data.db.dao.LoadDao
import com.myhealth.data.db.dao.MealDao
import com.myhealth.data.db.dao.NutritionDao
import com.myhealth.data.db.dao.PlanDao
import com.myhealth.data.db.dao.SleepDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.data.mapper.toSummary
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.timeZone
import com.myhealth.domain.engine.calendar.EventActivityLinker
import com.myhealth.domain.engine.calendar.LinkProposal
import com.myhealth.domain.engine.calendar.RecurrenceRule
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventOverride
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.model.MealLogSummary
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.SportType
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/**
 * Room-backed [CalendarRepository] (PLAN P3.2): the `calendar_event` / `event_override` CRUD plus
 * the [CalendarDay] aggregate of §2.3.
 *
 * [observeRange] combines the six calendar sources (events, planned sessions, activities, meals,
 * target snapshots, daily load) plus the night's sleep, and hands the lists to the pure
 * [CalendarAggregator] on [computeDispatcher] (`Dispatchers.Default`) — `combine` has no typed
 * overload past five flows, so the inputs are combined in three groups and merged.
 */
class RoomCalendarRepository(
    private val eventDao: EventDao,
    private val planDao: PlanDao,
    private val activityDao: ActivityDao,
    private val mealDao: MealDao,
    private val nutritionDao: NutritionDao,
    private val loadDao: LoadDao,
    private val sleepDao: SleepDao,
    private val activityRepo: ActivityRepository,
    private val clock: PlatformClock,
    /**
     * Called after every event/override write (POLISH-8). Wired to
     * `SuggestionRepository.markProposedStale`, so a calendar edit under an open `PROPOSED` batch
     * makes Training and the Today card offer "Calendar changed — regenerate".
     */
    private val onPlanChanged: suspend () -> Unit = {},
    private val zone: TimeZone = clock.timeZone,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CalendarRepository {

    override fun observeRange(fromDay: Long, toDay: Long): Flow<Map<Long, CalendarDay>> {
        val calendar = combine(
            eventDao.observeOverlapping(fromDay, toDay).map { rows -> rows.map { it.toDomain() } },
            eventDao.observeOverridesInRange(fromDay, toDay).map { rows -> rows.map { it.toDomain() } },
            planDao.observeSessionRange(fromDay, toDay).map { rows -> rows.map { it.toDomain() } },
            ::CalendarInputs,
        )
        val logged = combine(
            activityDao.observeRange(fromDay, toDay).map { rows -> rows.map { it.toSummary() } },
            mealDao.observeRange(fromDay, toDay).map { rows -> rows.map { it.toSummary() } },
            sleepDao.observeRange(fromDay, toDay).map { rows -> rows.map { it.toDomain() } },
            ::LoggedInputs,
        )
        val derived = combine(
            nutritionDao.observeTargets(fromDay, toDay).map { rows -> rows.map { it.toDomain() } },
            loadDao.observeRange(fromDay, toDay).map { rows -> rows.map { it.toDomain() } },
            ::DerivedInputs,
        )
        return combine(calendar, logged, derived) { events, actual, cached ->
            withContext(computeDispatcher) {
                CalendarAggregator.aggregate(
                    fromDay = fromDay,
                    toDay = toDay,
                    events = events.events,
                    overrides = events.overrides,
                    planned = events.planned,
                    activities = actual.activities,
                    meals = actual.meals,
                    targets = cached.targets,
                    loads = cached.loads,
                    sleep = actual.sleep,
                )
            }
        }
    }

    override fun observeDay(day: Long): Flow<CalendarDay> =
        observeRange(day, day).map { it[day] ?: CalendarDay.empty(day) }

    override fun observeOccurrences(fromDay: Long, toDay: Long): Flow<List<EventOccurrence>> =
        combine(
            eventDao.observeOverlapping(fromDay, toDay).map { rows -> rows.map { it.toDomain() } },
            eventDao.observeOverridesInRange(fromDay, toDay).map { rows -> rows.map { it.toDomain() } },
        ) { events, overrides ->
            CalendarAggregator.expand(events, overrides, fromDay, toDay)
        }

    override fun observeKeyEvents(fromDay: Long): Flow<List<CalendarEvent>> =
        eventDao.observeKeyEvents(fromDay).map { rows -> rows.map { it.toDomain() } }

    override fun observeLinkProposals(fromDay: Long, toDay: Long): Flow<List<LinkProposal>> =
        combine(
            observeOccurrences(fromDay, toDay),
            activityDao.observeRange(fromDay, toDay).map { rows -> rows.map { it.toSummary() } },
        ) { occurrences, activities ->
            withContext(computeDispatcher) {
                EventActivityLinker.propose(occurrences, activities, zone)
            }
        }

    override suspend fun getEvent(id: Long): CalendarEvent? =
        withContext(ioDispatcher) { eventDao.getById(id)?.toDomain() }

    /** Timestamps and the denormalized `recurrenceUntilDay` are maintained here, not by callers. */
    override suspend fun upsertEvent(event: CalendarEvent): Outcome<Long> =
        withContext(ioDispatcher) {
            runCatchingApp {
                val now = clock.millis()
                val entity = event
                    .copy(
                        recurrenceUntilDay = event.recurrenceUntilDay
                            ?: RecurrenceRule.parse(event.recurrenceRule)?.untilDay,
                        createdAtMillis = if (event.createdAtMillis == 0L) now else event.createdAtMillis,
                        updatedAtMillis = now,
                    )
                    .toEntity()
                val newId = eventDao.upsert(entity)
                onPlanChanged()
                if (entity.id != 0L) entity.id else newId
            }
        }

    override suspend fun deleteEvent(id: Long): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp {
                eventDao.deleteById(id)
                onPlanChanged()
            }
        }

    override suspend fun upsertOverride(override: EventOverride): Outcome<Long> =
        withContext(ioDispatcher) {
            runCatchingApp {
                val entity = override.toEntity()
                val newId = eventDao.upsertOverride(entity)
                onPlanChanged()
                if (entity.id != 0L) entity.id else newId
            }
        }

    override suspend fun deleteOverride(id: Long): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp {
                eventDao.deleteOverrideById(id)
                onPlanChanged()
            }
        }

    /**
     * Links (or unlinks, with `activityId = null`) an activity to an event. Linking a
     * `SOCCER_MATCH` event upgrades the activity's sport to [SportType.SOCCER_MATCH] (P3.7): the
     * upgrade goes through [ActivityRepository.setSportType], which pins the field in
     * `userEditedFields` so a later Health Connect merge can never revert it (§2.4).
     */
    override suspend fun linkActivity(
        eventId: Long,
        activityId: Long?,
        method: LinkMethod?,
    ): Outcome<Unit> {
        val event = withContext(ioDispatcher) { eventDao.getById(eventId) }
            ?: return Outcome.Err(AppError.Validation("eventId", "no event with id $eventId"))
        val linked = withContext(ioDispatcher) {
            runCatchingApp { eventDao.linkActivity(eventId, activityId, method) }
        }
        if (linked is Outcome.Err) return linked
        if (activityId != null && event.type == EventType.SOCCER_MATCH) {
            return activityRepo.setSportType(activityId, SportType.SOCCER_MATCH)
        }
        return Outcome.Ok(Unit)
    }

    private data class CalendarInputs(
        val events: List<CalendarEvent>,
        val overrides: List<EventOverride>,
        val planned: List<PlannedSession>,
    )

    private data class LoggedInputs(
        val activities: List<ActivitySummary>,
        val meals: List<MealLogSummary>,
        val sleep: List<SleepRecord>,
    )

    private data class DerivedInputs(
        val targets: List<NutritionTarget>,
        val loads: List<DailyLoad>,
    )
}
