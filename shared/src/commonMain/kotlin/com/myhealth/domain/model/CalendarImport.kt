package com.myhealth.domain.model

import kotlinx.serialization.Serializable

/** A calendar the device knows (the phone's calendar store). Never stored. */
data class DeviceCalendar(
    val id: Long,
    val name: String,
    /** `true` for a calendar that exists only on this device (no account behind it). */
    val isLocal: Boolean,
)

/** One occurrence in a [DeviceCalendar]; recurring entries arrive already expanded. */
data class DeviceCalendarEntry(
    val calendarId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String?,
)

/** How the entries of one group are brought into MyHealth. */
@Serializable
data class CalendarClusterRule(
    val enabled: Boolean,
    val type: EventType,
    /** `null` keeps each entry's own length. */
    val durationMin: Int? = null,
    val isKeyEvent: Boolean = false,
)

/** A calendar chosen for the import; [name] finds it again if the device hands out a new [id]. */
@Serializable
data class SelectedCalendar(val id: Long, val name: String)

/**
 * The calendar import's settings (device-local, one JSON value in the settings file).
 *
 * [rules] is keyed by [ruleKey]. [imported] lists what the import has written (see
 * [importedKey]): an entry that is listed but has no event any more was removed in the app and
 * is not brought back.
 */
@Serializable
data class CalendarImportConfig(
    val calendars: List<SelectedCalendar> = emptyList(),
    val rules: Map<String, CalendarClusterRule> = emptyMap(),
    val imported: Set<String> = emptySet(),
    val lastCheckedAtMillis: Long? = null,
) {
    companion object {
        const val SOURCE_PREFIX: String = "calendar:"

        fun sourceOf(calendarName: String): String = SOURCE_PREFIX + calendarName

        fun ruleKey(calendarName: String, clusterKey: String): String = "$calendarName\n$clusterKey"

        fun importedKey(source: String, externalId: String): String = "$source\n$externalId"
    }
}
