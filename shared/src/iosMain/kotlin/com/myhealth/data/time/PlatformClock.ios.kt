package com.myhealth.data.time

import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** The system clock in the device's current zone; tests subclass it with fixed values. */
actual abstract class PlatformClock {
    actual open fun millis(): Long = Clock.System.now().toEpochMilliseconds()

    open val zone: TimeZone get() = TimeZone.currentSystemDefault()
}

/** The device clock. */
object SystemPlatformClock : PlatformClock()

actual val PlatformClock.timeZone: TimeZone
    get() = zone

actual fun systemClock(): PlatformClock = SystemPlatformClock
