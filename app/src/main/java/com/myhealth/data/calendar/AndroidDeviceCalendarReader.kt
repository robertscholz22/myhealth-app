package com.myhealth.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.myhealth.domain.model.DeviceCalendar
import com.myhealth.domain.model.DeviceCalendarEntry
import com.myhealth.domain.device.DeviceCalendarReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [DeviceCalendarReader] on Android's calendar provider. Read-only: this class issues queries and
 * nothing else. Occurrences come from the provider's `instances` view, which expands recurring
 * entries; cancelled and deleted ones are skipped here.
 */
class AndroidDeviceCalendarReader(private val context: Context) : DeviceCalendarReader {

    override val supported: Boolean = true

    override fun hasAccess(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun calendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val result = mutableListOf<DeviceCalendar>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)?.trim().orEmpty()
                if (name.isEmpty()) continue
                result += DeviceCalendar(
                    id = c.getLong(0),
                    name = name,
                    isLocal = c.getString(2) == CalendarContract.ACCOUNT_TYPE_LOCAL,
                )
            }
        }
        result
    }

    override suspend fun entries(calendarIds: Set<Long>, fromMillis: Long, toMillis: Long): List<DeviceCalendarEntry> =
        withContext(Dispatchers.IO) {
            if (!hasAccess() || calendarIds.isEmpty()) return@withContext emptyList()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .also { ContentUris.appendId(it, fromMillis) }
                .also { ContentUris.appendId(it, toMillis) }
                .build()
            val projection = arrayOf(
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.STATUS,
                CalendarContract.Events.DELETED,
            )
            val result = mutableListOf<DeviceCalendarEntry>()
            context.contentResolver.query(uri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    val calendarId = c.getLong(0)
                    val begin = c.getLong(2)
                    // The view also returns entries that started earlier and reach into the window.
                    if (calendarId !in calendarIds || begin < fromMillis) continue
                    if (!c.isNull(6) && c.getInt(6) == CalendarContract.Events.STATUS_CANCELED) continue
                    // Some providers keep the occurrences of an entry its app marked as deleted.
                    if (!c.isNull(7) && c.getInt(7) != 0) continue
                    result += DeviceCalendarEntry(
                        calendarId = calendarId,
                        title = c.getString(1).orEmpty(),
                        startMillis = begin,
                        endMillis = if (c.isNull(3)) begin else c.getLong(3),
                        allDay = c.getInt(4) != 0,
                        location = c.getString(5)?.takeIf { it.isNotBlank() },
                    )
                }
            }
            result
        }
}
