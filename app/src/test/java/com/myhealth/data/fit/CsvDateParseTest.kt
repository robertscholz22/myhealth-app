package com.myhealth.data.fit

import com.google.common.truth.Truth.assertWithMessage
import kotlinx.datetime.TimeZone
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.random.Random

/**
 * P20.2: the common [parseDateTime] replaces seven `DateTimeFormatter.ofPattern` layouts plus
 * `LocalDate.parse`. [javaParse] is the pre-P20.2 code, verbatim; both must agree on every
 * input, including the SMART resolver's quirks (31 Feb → 28/29 Feb, `24:00:00` → next midnight)
 * and the daylight-saving gap and overlap.
 */
class CsvDateParseTest {

    private val formats = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "dd.MM.yyyy HH:mm:ss",
        "dd.MM.yyyy HH:mm",
        "dd/MM/yyyy HH:mm:ss",
        "MM/dd/yyyy HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss",
    ).map { DateTimeFormatter.ofPattern(it) }

    private fun javaParse(raw: String, zone: ZoneId): Long? {
        val value = raw.trim().removeSuffix("Z")
        for (format in formats) {
            try {
                return LocalDateTime.parse(value, format).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Try the next known layout.
            }
        }
        return try {
            LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private val zones = listOf("Europe/Berlin", "America/New_York", "UTC", "Asia/Kolkata")

    private fun check(raw: String) {
        for (id in zones) {
            assertWithMessage("'$raw' in $id")
                .that(parseDateTime(raw, TimeZone.of(id)))
                .isEqualTo(javaParse(raw, ZoneId.of(id)))
        }
    }

    @Test
    fun csvd01_known_layouts_and_edge_values() {
        listOf(
            "2026-09-14 07:30:05", "2026-09-14 07:30", "14.09.2026 07:30:05", "14.09.2026 07:30",
            "14/09/2026 07:30:05", "09/14/2026 07:30:05", "12/11/2026 07:30:05", "2026-09-14T07:30:05",
            "2026-09-14T07:30:05Z", "  2026-09-14 07:30:05  ", "2026-09-14", "2026-02-30", "2026-13-01",
            "2026-02-31 10:00:00", "2024-02-30 10:00", "31.04.2026 10:00", "31/06/2026 10:00:00",
            "2026-09-14 24:00:00", "2026-09-14 24:00", "2026-12-31 24:00:00", "2026-09-14 24:01:00",
            "2026-09-14 23:60:00", "2026-09-14 23:59:60", "2026-09-14 25:00:00", "2026-00-10 10:00:00",
            "2026-09-00 10:00:00", "2026-09-32 10:00:00", "2026-9-14 07:30:05", "2026-09-14 7:30:05",
            "2026-09-14 07:30:05.5", "14.9.2026 07:30", "", "--", "today", "2026-09-14 07:30:05 ",
            // Europe/Berlin and New York daylight-saving gaps and overlaps.
            "2026-03-29 02:30:00", "2026-10-25 02:30:00", "2026-03-08 02:30:00", "2026-11-01 01:30:00",
            "2026-03-29", "1900-01-01 00:00:00", "2400-02-29 12:00:00", "2100-02-29 12:00:00",
        ).forEach(::check)
    }

    @Test
    fun csvd02_random_fields_in_every_layout() {
        val random = Random(29)
        fun two(max: Int) = random.nextInt(0, max).toString().padStart(2, '0')
        repeat(20_000) {
            val y = (1990 + random.nextInt(60)).toString()
            val m = two(14)
            val d = two(33)
            val h = two(26)
            val mi = two(61)
            val s = two(61)
            val raw = when (random.nextInt(8)) {
                0 -> "$y-$m-$d $h:$mi:$s"
                1 -> "$y-$m-$d $h:$mi"
                2 -> "$d.$m.$y $h:$mi:$s"
                3 -> "$d.$m.$y $h:$mi"
                4 -> "$d/$m/$y $h:$mi:$s"
                5 -> "$y-$m-${d}T$h:$mi:$s"
                6 -> "$y-$m-$d"
                else -> "$m/$d/$y $h:$mi:$s"
            }
            check(raw)
        }
    }
}
