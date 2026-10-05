package com.myhealth.data.mapper

import com.myhealth.data.db.entity.CalendarEventEntity
import com.myhealth.data.db.entity.EventOverrideEntity
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.EventOverride

/** `calendar_event` and `event_override` ⇄ the domain calendar models (PLAN §2.2.4 / P1.6). */

fun CalendarEventEntity.toDomain(): CalendarEvent = CalendarEvent(
    id = id,
    type = type,
    title = title,
    startDay = startDay,
    startMinuteOfDay = startMinuteOfDay,
    durationMin = durationMin,
    location = location,
    sportType = sportType,
    targetDistanceMeters = targetDistanceMeters,
    isKeyEvent = isKeyEvent,
    recurrenceRule = recurrenceRule,
    recurrenceUntilDay = recurrenceUntilDay,
    parentEventId = parentEventId,
    linkedActivityId = linkedActivityId,
    linkMethod = linkMethod,
    notes = notes,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun CalendarEvent.toEntity(): CalendarEventEntity = CalendarEventEntity(
    id = id,
    type = type,
    title = title,
    startDay = startDay,
    startMinuteOfDay = startMinuteOfDay,
    durationMin = durationMin,
    location = location,
    sportType = sportType,
    targetDistanceMeters = targetDistanceMeters,
    isKeyEvent = isKeyEvent,
    recurrenceRule = recurrenceRule,
    recurrenceUntilDay = recurrenceUntilDay,
    parentEventId = parentEventId,
    linkedActivityId = linkedActivityId,
    linkMethod = linkMethod,
    notes = notes,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun EventOverrideEntity.toDomain(): EventOverride = EventOverride(
    id = id,
    eventId = eventId,
    occurrenceDay = occurrenceDay,
    action = action,
    newStartDay = newStartDay,
    newStartMinuteOfDay = newStartMinuteOfDay,
    newDurationMin = newDurationMin,
    newTitle = newTitle,
)

fun EventOverride.toEntity(): EventOverrideEntity = EventOverrideEntity(
    id = id,
    eventId = eventId,
    occurrenceDay = occurrenceDay,
    action = action,
    newStartDay = newStartDay,
    newStartMinuteOfDay = newStartMinuteOfDay,
    newDurationMin = newDurationMin,
    newTitle = newTitle,
)
