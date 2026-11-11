package com.myhealth.domain.engine.calendar

import com.myhealth.domain.model.CalendarClusterRule
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.CalendarImportConfig
import com.myhealth.domain.model.DeviceCalendarEntry
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.SportType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A chosen calendar that the device currently has, with its entries already grouped. */
data class ClusteredCalendar(val name: String, val clusters: List<EntryCluster>)

/**
 * What one import run changes. [imported] replaces [CalendarImportConfig.imported];
 * [removedInApp] counts entries that stay away because their event was deleted in the app.
 */
data class CalendarImportPlan(
    val upserts: List<CalendarEvent>,
    val deleteIds: List<Long>,
    val imported: Set<String>,
    val added: Int,
    val updated: Int,
    val removedInApp: Int,
    /** Upcoming entries that are events after this run. */
    val active: Int,
) {
    val isNoop: Boolean get() = upserts.isEmpty() && deleteIds.isEmpty()
}

/**
 * Turns grouped calendar entries and their rules into event writes (pure, no I/O).
 *
 * - Only entries from [todayStartMillis] on are imported; what lies in the past is history and is
 *   never touched again.
 * - An entry is recognised by calendar, start and title. The same appointment written twice by
 *   the calendar app is one event; an appointment that moves is a removal plus a new event.
 * - An upcoming imported event whose entry is gone (cancelled, group switched off, calendar no
 *   longer chosen) is deleted, unless an activity is linked to it.
 * - Calendars in [unavailableSources] are chosen but cannot be read right now (removed, access
 *   withdrawn): their events are left alone.
 * - An entry whose event was deleted in the app is not written again.
 */
object CalendarImportPlanner {

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MAX_DURATION_MIN = 24 * 60
    private val WHITESPACE = Regex("\\s+")

    fun externalId(entry: DeviceCalendarEntry): String =
        "${entry.startMillis}|${CalendarClusterer.normalize(entry.title)}"

    fun plan(
        calendars: List<ClusteredCalendar>,
        rules: Map<String, CalendarClusterRule>,
        existing: List<CalendarEvent>,
        previouslyImported: Set<String>,
        unavailableSources: Set<String>,
        todayStartMillis: Long,
        todayEpochDay: Long,
        zone: TimeZone,
    ): CalendarImportPlan {
        val desired = LinkedHashMap<String, CalendarEvent>()
        for (calendar in calendars) {
            val source = CalendarImportConfig.sourceOf(calendar.name)
            for (cluster in calendar.clusters) {
                val rule = rules[CalendarImportConfig.ruleKey(calendar.name, cluster.key)] ?: continue
                if (!rule.enabled) continue
                for (entry in cluster.entries) {
                    if (entry.startMillis < todayStartMillis) continue
                    val id = externalId(entry)
                    desired.getOrPut(CalendarImportConfig.importedKey(source, id)) {
                        eventOf(entry, rule, source, id, zone)
                    }
                }
            }
        }

        val imports = existing.filter { it.externalSource?.startsWith(CalendarImportConfig.SOURCE_PREFIX) == true }
        val existingByKey = imports.associateBy { CalendarImportConfig.importedKey(it.externalSource!!, it.externalId.orEmpty()) }

        val upserts = mutableListOf<CalendarEvent>()
        var added = 0
        var updated = 0
        var removedInApp = 0
        val imported = previouslyImported
            .filter { key -> unavailableSources.any { key.startsWith(it + "\n") } }
            .toMutableSet()
        for ((key, wanted) in desired) {
            val current = existingByKey[key]
            when {
                current != null -> {
                    imported += key
                    val merged = current.copy(
                        type = wanted.type,
                        title = wanted.title,
                        startDay = wanted.startDay,
                        startMinuteOfDay = wanted.startMinuteOfDay,
                        durationMin = wanted.durationMin,
                        location = wanted.location,
                        sportType = wanted.sportType,
                        isKeyEvent = wanted.isKeyEvent,
                    )
                    if (merged != current) {
                        upserts += merged
                        updated++
                    }
                }
                key in previouslyImported -> {
                    imported += key
                    removedInApp++
                }
                else -> {
                    imported += key
                    upserts += wanted
                    added++
                }
            }
        }

        val deleteIds = imports
            .filter { event ->
                CalendarImportConfig.importedKey(event.externalSource!!, event.externalId.orEmpty()) !in desired &&
                    event.startDay >= todayEpochDay &&
                    event.linkedActivityId == null &&
                    event.externalSource !in unavailableSources
            }
            .map { it.id }

        return CalendarImportPlan(upserts, deleteIds, imported, added, updated, removedInApp, desired.size - removedInApp)
    }

    private fun eventOf(
        entry: DeviceCalendarEntry,
        rule: CalendarClusterRule,
        source: String,
        externalId: String,
        zone: TimeZone,
    ): CalendarEvent {
        // An all-day entry is stored as UTC midnight to UTC midnight, whatever the device's zone.
        val start = Instant.fromEpochMilliseconds(entry.startMillis)
            .toLocalDateTime(if (entry.allDay) TimeZone.UTC else zone)
        val ownMinutes = ((entry.endMillis - entry.startMillis) / MILLIS_PER_MINUTE).toInt()
        return CalendarEvent(
            id = 0,
            type = rule.type,
            title = entry.title.replace(WHITESPACE, " ").trim().trimEnd(',', ' '),
            startDay = start.date.toEpochDays(),
            startMinuteOfDay = if (entry.allDay) null else start.time.hour * 60 + start.time.minute,
            durationMin = if (entry.allDay) null else rule.durationMin ?: ownMinutes.takeIf { it in 1..MAX_DURATION_MIN },
            location = cleanLocation(entry.location),
            sportType = sportOf(rule.type),
            targetDistanceMeters = null,
            isKeyEvent = rule.isKeyEvent,
            recurrenceRule = null,
            recurrenceUntilDay = null,
            parentEventId = null,
            linkedActivityId = null,
            linkMethod = null,
            notes = null,
            createdAtMillis = 0,
            updatedAtMillis = 0,
            externalSource = source,
            externalId = externalId,
        )
    }

    private fun sportOf(type: EventType): SportType? = when (type) {
        EventType.SOCCER_MATCH -> SportType.SOCCER_MATCH
        EventType.SOCCER_TRAINING -> SportType.SOCCER_TRAINING
        else -> null
    }

    /** Address parts on one line; empty parts ("Pitch ,\n, ,") dropped. */
    internal fun cleanLocation(raw: String?): String? =
        raw?.split(',', '\n')
            ?.map { it.replace(WHITESPACE, " ").trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(", ")
            ?.takeIf { it.isNotEmpty() }
}
