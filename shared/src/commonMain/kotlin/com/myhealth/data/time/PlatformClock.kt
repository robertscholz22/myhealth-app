package com.myhealth.data.time

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The wall clock the data layer reads "now" and "today" from (§1.3: injected, so tests pin it).
 *
 * P20.2: on Android this *is* `java.time.Clock` (a typealias), so the app and its tests keep
 * passing the same clocks they always did; iOS has its own class.
 */
expect abstract class PlatformClock {
    open fun millis(): Long
}

/** The clock's time zone — the zone local days are cut in. */
expect val PlatformClock.timeZone: TimeZone

/** Today's epoch day in the clock's zone. */
fun PlatformClock.todayEpochDay(): Long =
    Instant.fromEpochMilliseconds(millis()).toLocalDateTime(timeZone).date.toEpochDays()

/** Today's date in the clock's zone (`LocalDate.now(clock)`). */
fun PlatformClock.today(): kotlinx.datetime.LocalDate = kotlinx.datetime.LocalDate.fromEpochDays(todayEpochDay())

/** Minute of day (0…1439) of [atMillis] in [zone]. */
fun minuteOfDay(atMillis: Long, zone: TimeZone): Int {
    val time = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone).time
    return time.hour * 60 + time.minute
}

/** The device clock in the device's zone. */
expect fun systemClock(): PlatformClock

/** The clock as a `kotlin.time.Clock` for the domain engines (millisecond precision, as on Android). */
fun PlatformClock.toKotlinClock(): kotlin.time.Clock {
    val source = this
    return object : kotlin.time.Clock {
        override fun now(): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(source.millis())
    }
}
