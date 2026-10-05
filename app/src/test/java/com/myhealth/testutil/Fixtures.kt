package com.myhealth.testutil

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Shared test helpers. Engines take a [Clock] so "today" can be pinned (§1.3).
 */
object Fixtures {

    /** UTC is the default test zone: deterministic, no DST surprises. */
    val ZONE: ZoneId = ZoneId.of("UTC")

    /** [ZONE] for the shared (kotlinx-datetime) engines. */
    val TZ: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.UTC

    /**
     * A fixed [Clock] from an ISO-8601 string.
     * Accepts an instant (`2026-09-12T07:30:00Z`) or a local date-time (`2026-09-12T07:30`),
     * or a bare date (`2026-09-12`, taken as midnight).
     */
    fun fixedClock(iso: String, zone: ZoneId = ZONE): Clock =
        Clock.fixed(parseInstant(iso, zone), zone)

    /** Epoch day of an ISO date string — the Room storage form for local days (§1.6). */
    fun epochDay(isoDate: String): Long = LocalDate.parse(isoDate).toEpochDay()

    /** Epoch millis of an ISO instant / local date-time / date. */
    fun millis(iso: String, zone: ZoneId = ZONE): Long = parseInstant(iso, zone).toEpochMilli()

    private fun parseInstant(iso: String, zone: ZoneId): Instant = when {
        iso.endsWith("Z") || iso.contains('+') -> Instant.parse(iso)
        iso.contains('T') -> LocalDateTime.parse(iso).atZone(zone).toInstant()
        else -> LocalDate.parse(iso).atStartOfDay(zone).toInstant()
    }

    /** Convenience for tests that want a UTC offset explicitly. */
    val OFFSET: ZoneOffset = ZoneOffset.UTC
}
