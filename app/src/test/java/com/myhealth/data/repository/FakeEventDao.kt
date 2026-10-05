package com.myhealth.data.repository

import com.myhealth.data.db.dao.EventDao
import com.myhealth.data.db.entity.CalendarEventEntity
import com.myhealth.data.db.entity.EventOverrideEntity
import com.myhealth.domain.model.LinkMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [EventDao] for the calendar-repository tests (PLAN P3.2). [observeOverlapping]
 * reproduces the deliberate over-selection of the real query: a one-off inside the window, or any
 * recurring definition that starts at/before it and has not ended before it.
 */
class FakeEventDao : EventDao {

    val events = MutableStateFlow<Map<Long, CalendarEventEntity>>(emptyMap())
    val overrides = MutableStateFlow<Map<Long, EventOverrideEntity>>(emptyMap())

    private var nextEventId = 1L
    private var nextOverrideId = 1L

    override suspend fun upsert(entity: CalendarEventEntity): Long {
        val id = if (entity.id == 0L) nextEventId++ else entity.id
        events.value = events.value + (id to entity.copy(id = id))
        return id
    }

    override suspend fun getById(id: Long): CalendarEventEntity? = events.value[id]

    override suspend fun deleteById(id: Long) {
        events.value = events.value - id
        overrides.value = overrides.value.filterValues { it.eventId != id }
    }

    override fun observeOverlapping(
        fromDay: Long,
        toDay: Long,
    ): Flow<List<CalendarEventEntity>> = events.map { rows ->
        rows.values
            .filter { row ->
                row.startDay <= toDay && when (row.recurrenceRule) {
                    null -> row.startDay >= fromDay
                    else -> (row.recurrenceUntilDay ?: Long.MAX_VALUE) >= fromDay
                }
            }
            .sortedWith(compareBy({ it.startDay }, { it.startMinuteOfDay ?: -1 }))
    }

    override fun observeKeyEvents(fromDay: Long): Flow<List<CalendarEventEntity>> =
        events.map { rows ->
            rows.values.filter { it.isKeyEvent && it.startDay >= fromDay }.sortedBy { it.startDay }
        }

    override suspend fun getByLinkedActivity(activityId: Long): List<CalendarEventEntity> =
        events.value.values.filter { it.linkedActivityId == activityId }

    override suspend fun linkActivity(id: Long, activityId: Long?, linkMethod: LinkMethod?) {
        val row = events.value[id] ?: return
        events.value = events.value +
            (id to row.copy(linkedActivityId = activityId, linkMethod = linkMethod))
    }

    override suspend fun upsertOverride(entity: EventOverrideEntity): Long {
        val id = if (entity.id == 0L) nextOverrideId++ else entity.id
        overrides.value = overrides.value + (id to entity.copy(id = id))
        return id
    }

    override fun observeOverrides(eventId: Long): Flow<List<EventOverrideEntity>> =
        overrides.map { rows ->
            rows.values.filter { it.eventId == eventId }.sortedBy { it.occurrenceDay }
        }

    override fun observeOverridesInRange(
        fromDay: Long,
        toDay: Long,
    ): Flow<List<EventOverrideEntity>> = overrides.map { rows ->
        rows.values.filter {
            it.occurrenceDay in fromDay..toDay || (it.newStartDay ?: Long.MIN_VALUE) in fromDay..toDay
        }
    }

    override suspend fun deleteOverrideById(id: Long) {
        overrides.value = overrides.value - id
    }
}
