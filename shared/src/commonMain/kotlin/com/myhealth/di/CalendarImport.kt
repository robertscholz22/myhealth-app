package com.myhealth.di

import com.myhealth.domain.engine.calendar.EntryCluster
import com.myhealth.domain.model.CalendarClusterRule
import com.myhealth.domain.model.DeviceCalendar
import com.myhealth.domain.model.DeviceCalendarEntry

/** A device calendar as the import screen lists it. */
data class CalendarChoice(val calendar: DeviceCalendar, val selected: Boolean, val upcoming: Int)

/** One group of a chosen calendar with the rule that applies to it. */
data class CalendarGroup(
    val calendarName: String,
    val cluster: EntryCluster,
    val rule: CalendarClusterRule,
    val upcoming: List<DeviceCalendarEntry>,
)

/** Everything the import screen shows, taken after an import run. */
data class CalendarImportOverview(
    val supported: Boolean = true,
    val hasAccess: Boolean = false,
    val calendars: List<CalendarChoice> = emptyList(),
    val groups: List<CalendarGroup> = emptyList(),
    /** Chosen calendars the device no longer lists. */
    val missingCalendars: List<String> = emptyList(),
    /** Upcoming events in MyHealth that came from the chosen calendars. */
    val importedUpcoming: Int = 0,
    /** Upcoming entries that stay away because their event was deleted in the app. */
    val removedInApp: Int = 0,
    val editedInApp: Int = 0,
    val lastCheckedAtMillis: Long? = null,
)

/**
 * The calendar import as the UI sees it (`ui/` may not import `data/`). Every call that changes
 * something runs the import right away and returns the state after it.
 */
interface CalendarImport {
    suspend fun refresh(): CalendarImportOverview
    suspend fun setSelected(calendar: DeviceCalendar, selected: Boolean): CalendarImportOverview
    suspend fun setRule(calendarName: String, clusterKey: String, rule: CalendarClusterRule): CalendarImportOverview

    /** Writes the entries again whose events were deleted in the app. */
    suspend fun restoreRemoved(): CalendarImportOverview
}
