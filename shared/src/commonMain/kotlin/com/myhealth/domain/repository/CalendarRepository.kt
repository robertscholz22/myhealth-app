package com.myhealth.domain.repository

import com.myhealth.domain.engine.calendar.LinkProposal
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventOverride
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `calendar_event` + `event_override` (PLAN §2.2.4) and the [CalendarDay] aggregate (§2.3, P3.2).
 *
 * [observeRange] combines six sources (events, planned sessions, activities, meals, targets,
 * load) and is keyed by epoch day; days with no data are present with empty collections so the
 * calendar grid never has to guess. [observeOccurrences] exposes just the expanded recurrence
 * (P3.1) for screens that do not need the rest of the aggregate.
 */
interface CalendarRepository {

    fun observeRange(fromDay: Long, toDay: Long): Flow<Map<Long, CalendarDay>>

    fun observeDay(day: Long): Flow<CalendarDay>

    fun observeOccurrences(fromDay: Long, toDay: Long): Flow<List<EventOccurrence>>

    fun observeKeyEvents(fromDay: Long): Flow<List<CalendarEvent>>

    /**
     * Auto-link suggestions (P3.3) for the unlinked events and activities in the window, so the
     * linking UI (P3.7) does not have to run the engine itself.
     */
    fun observeLinkProposals(fromDay: Long, toDay: Long): Flow<List<LinkProposal>>

    suspend fun getEvent(id: Long): CalendarEvent?

    suspend fun upsertEvent(event: CalendarEvent): Outcome<Long>

    suspend fun deleteEvent(id: Long): Outcome<Unit>

    suspend fun upsertOverride(override: EventOverride): Outcome<Long>

    suspend fun deleteOverride(id: Long): Outcome<Unit>

    /** Event ↔ activity linking (P3.3, P3.7); `activityId = null` unlinks. */
    suspend fun linkActivity(eventId: Long, activityId: Long?, method: LinkMethod?): Outcome<Unit>
}
