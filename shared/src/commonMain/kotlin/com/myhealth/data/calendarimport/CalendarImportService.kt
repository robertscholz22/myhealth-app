package com.myhealth.data.calendarimport

import com.myhealth.data.log.PlatformLog
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.timeZone
import com.myhealth.data.time.todayEpochDay
import com.myhealth.di.CalendarChoice
import com.myhealth.di.CalendarGroup
import com.myhealth.di.CalendarImport
import com.myhealth.di.CalendarImportOverview
import com.myhealth.domain.engine.calendar.CalendarClusterer
import com.myhealth.domain.engine.calendar.CalendarImportPlanner
import com.myhealth.domain.engine.calendar.ClusteredCalendar
import com.myhealth.domain.model.CalendarClusterRule
import com.myhealth.domain.model.CalendarImportConfig
import com.myhealth.domain.model.DeviceCalendar
import com.myhealth.domain.model.SelectedCalendar
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.device.DeviceCalendarReader
import com.myhealth.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json

/**
 * Brings appointments from the device's calendars into `calendar_event` (v10).
 *
 * One run reads the chosen calendars, groups their entries ([CalendarClusterer]), lets
 * [CalendarImportPlanner] decide what to write, and applies it. It runs when the import screen
 * changes something, at app start and with every background sync; without chosen calendars it
 * does nothing. The device calendar is only ever read.
 */
class CalendarImportService(
    private val reader: DeviceCalendarReader,
    private val calendarRepo: CalendarRepository,
    private val settings: SettingsRepository,
    private val clock: PlatformClock,
    /** Called after a run that changed events (an open plan proposal is then out of date). */
    private val onEventsChanged: suspend () -> Unit,
) : CalendarImport {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    override suspend fun refresh(): CalendarImportOverview = mutex.withLock { run() }

    override suspend fun setSelected(calendar: DeviceCalendar, selected: Boolean): CalendarImportOverview =
        mutex.withLock {
            edit { config ->
                val others = config.calendars.filterNot { it.id == calendar.id }
                config.copy(calendars = if (selected) others + SelectedCalendar(calendar.id, calendar.name) else others)
            }
            run()
        }

    override suspend fun setRule(calendarName: String, clusterKey: String, rule: CalendarClusterRule): CalendarImportOverview =
        mutex.withLock {
            edit { it.copy(rules = it.rules + (CalendarImportConfig.ruleKey(calendarName, clusterKey) to rule)) }
            run()
        }

    override suspend fun restoreRemoved(): CalendarImportOverview = mutex.withLock {
        val existing = calendarRepo.importedEvents()
            .map { CalendarImportConfig.importedKey(it.externalSource.orEmpty(), it.externalId.orEmpty()) }
            .toSet()
        edit { it.copy(imported = it.imported intersect existing) }
        run()
    }

    /** The background entry point: same run, failures only logged. */
    suspend fun sync() {
        try {
            mutex.withLock { if (config().calendars.isNotEmpty()) run() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PlatformLog.w(TAG, "calendar import failed: ${e.message}")
        }
    }

    private suspend fun run(): CalendarImportOverview {
        val config = config()
        if (!reader.supported) return CalendarImportOverview(supported = false)
        if (!reader.hasAccess()) {
            return CalendarImportOverview(hasAccess = false, lastCheckedAtMillis = config.lastCheckedAtMillis)
        }

        val zone = clock.timeZone
        val today = clock.todayEpochDay()
        val todayStart = LocalDate.fromEpochDays(today.toInt()).atStartOfDayIn(zone).toEpochMilliseconds()
        val from = todayStart - LOOK_BACK_DAYS * MILLIS_PER_DAY
        val to = todayStart + LOOK_AHEAD_DAYS * MILLIS_PER_DAY

        val device = reader.calendars()
        // A chosen calendar is found by id, or by name when the device recreated it under a new id.
        val resolved = config.calendars.map { chosen ->
            chosen to (device.firstOrNull { it.id == chosen.id } ?: device.singleOrNull { it.name == chosen.name })
        }
        val present = resolved.mapNotNull { it.second }.distinctBy { it.id }
        val missing = resolved.filter { it.second == null }.map { it.first.name }
        val entries = reader.entries(device.map { it.id }.toSet(), from, to).groupBy { it.calendarId }

        var rules = config.rules
        val clustered = present.groupBy { it.name }.map { (name, sameName) ->
            val known = rules.keys
                .filter { it.startsWith(name + "\n") }
                .map { it.substringAfter("\n") }
                .toSet()
            val clusters = CalendarClusterer.cluster(sameName.flatMap { entries[it.id].orEmpty() }, known)
            // A group keeps the rule it was first shown with, so a later run decides the same way.
            clusters.forEach { cluster ->
                val key = CalendarImportConfig.ruleKey(name, cluster.key)
                if (key !in rules) rules = rules + (key to CalendarClusterer.suggestedRule(cluster))
            }
            ClusteredCalendar(name, clusters)
        }

        val plan = CalendarImportPlanner.plan(
            calendars = clustered,
            rules = rules,
            existing = calendarRepo.importedEvents(),
            previouslyImported = config.imported,
            unavailableSources = missing.map { CalendarImportConfig.sourceOf(it) }.toSet(),
            todayStartMillis = todayStart,
            todayEpochDay = today,
            zone = zone,
        )
        plan.deleteIds.forEach { calendarRepo.deleteEvent(it) }
        plan.upserts.forEach { calendarRepo.upsertEvent(it) }
        val now = clock.millis()
        edit {
            it.copy(
                calendars = resolved.map { (chosen, found) -> found?.let { c -> SelectedCalendar(c.id, c.name) } ?: chosen },
                rules = rules,
                imported = plan.imported,
                lastCheckedAtMillis = now,
            )
        }
        if (!plan.isNoop) onEventsChanged()

        val selectedIds = present.map { it.id }.toSet()
        return CalendarImportOverview(
            hasAccess = true,
            calendars = device
                .map { calendar ->
                    CalendarChoice(
                        calendar = calendar,
                        selected = calendar.id in selectedIds,
                        upcoming = entries[calendar.id].orEmpty().count { it.startMillis >= todayStart },
                    )
                }
                .sortedWith(compareByDescending<CalendarChoice> { it.selected }.thenBy { it.calendar.name.lowercase() }),
            groups = clustered.flatMap { calendar ->
                calendar.clusters.map { cluster ->
                    CalendarGroup(
                        calendarName = calendar.name,
                        cluster = cluster,
                        rule = rules.getValue(CalendarImportConfig.ruleKey(calendar.name, cluster.key)),
                        upcoming = cluster.entries.filter { it.startMillis >= todayStart },
                    )
                }
            },
            missingCalendars = missing,
            importedUpcoming = plan.active,
            removedInApp = plan.removedInApp,
            lastCheckedAtMillis = now,
        )
    }

    private suspend fun config(): CalendarImportConfig {
        val raw = settings.settings.first().calendarImportJson
        if (raw.isBlank()) return CalendarImportConfig()
        return try {
            json.decodeFromString(CalendarImportConfig.serializer(), raw)
        } catch (e: Exception) {
            PlatformLog.w(TAG, "calendar import settings unreadable, starting over: ${e.message}")
            CalendarImportConfig()
        }
    }

    private suspend fun edit(transform: (CalendarImportConfig) -> CalendarImportConfig) {
        val next = transform(config())
        settings.update { it.copy(calendarImportJson = json.encodeToString(CalendarImportConfig.serializer(), next)) }
    }

    private companion object {
        const val TAG = "CalendarImport"
        const val MILLIS_PER_DAY = 86_400_000L

        /** Past entries only help to recognise groups; nothing before today is imported. */
        const val LOOK_BACK_DAYS = 60L
        const val LOOK_AHEAD_DAYS = 365L
    }
}
