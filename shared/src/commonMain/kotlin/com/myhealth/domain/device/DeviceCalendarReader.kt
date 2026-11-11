package com.myhealth.domain.device

import com.myhealth.domain.model.DeviceCalendar
import com.myhealth.domain.model.DeviceCalendarEntry

/**
 * Read-only window onto the calendars the device itself keeps (Android's calendar store). Other
 * apps — a team app, a mail account — put their appointments there; MyHealth only ever reads.
 */
interface DeviceCalendarReader {

    /** `false` where the platform offers no calendar store to this build. */
    val supported: Boolean

    fun hasAccess(): Boolean

    suspend fun calendars(): List<DeviceCalendar>

    /** Every occurrence that starts in `[fromMillis, toMillis]`; cancelled and deleted ones left out. */
    suspend fun entries(calendarIds: Set<Long>, fromMillis: Long, toMillis: Long): List<DeviceCalendarEntry>
}

/** For platforms without calendar access yet. */
object NoDeviceCalendarReader : DeviceCalendarReader {
    override val supported: Boolean = false
    override fun hasAccess(): Boolean = false
    override suspend fun calendars(): List<DeviceCalendar> = emptyList()
    override suspend fun entries(calendarIds: Set<Long>, fromMillis: Long, toMillis: Long): List<DeviceCalendarEntry> =
        emptyList()
}
