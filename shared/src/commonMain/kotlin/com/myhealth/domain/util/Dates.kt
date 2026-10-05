package com.myhealth.domain.util

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * `kotlinx-datetime` helpers for the shared code (PLAN §1.6, P20.1). Local dates are stored as
 * epoch day (`Long`), instants as epoch millis (`Long`).
 */

/** The date of this epoch day. */
fun Long.epochDayDate(): LocalDate = LocalDate.fromEpochDays(this)

/** The local calendar day (epoch day) the instant at these epoch millis falls on in [zone]. */
fun Long.epochMillisToDay(zone: TimeZone): Long =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(zone).date.toEpochDays()

fun LocalDate.plusDays(days: Long): LocalDate = plus(days, DateTimeUnit.DAY)

fun LocalDate.minusDays(days: Long): LocalDate = minus(days, DateTimeUnit.DAY)

fun LocalDate.plusWeeks(weeks: Long): LocalDate = plus(weeks, DateTimeUnit.WEEK)

fun LocalDate.minusWeeks(weeks: Long): LocalDate = minus(weeks, DateTimeUnit.WEEK)

/** `plusMonths`: the day is clamped to the end of a shorter month, as in `java.time`. */
fun LocalDate.plusMonths(months: Long): LocalDate = plus(months, DateTimeUnit.MONTH)

fun LocalDate.minusMonths(months: Long): LocalDate = minus(months, DateTimeUnit.MONTH)

/** The first day of this date's month (`withDayOfMonth(1)`). */
fun LocalDate.firstOfMonth(): LocalDate = LocalDate(year, month, 1)

/** Midnight UTC of this date in epoch millis — what Material's date picker works in. */
fun LocalDate.utcMidnightMillis(): Long = toEpochDays() * MILLIS_PER_DAY

/** The UTC date of epoch millis (the date picker's selection). */
fun utcDateOfMillis(millis: Long): LocalDate = LocalDate.fromEpochDays(floorDiv(millis, MILLIS_PER_DAY))

private const val MILLIS_PER_DAY = 86_400_000L

private fun floorDiv(a: Long, b: Long): Long = a / b - if (a % b != 0L && (a xor b) < 0) 1 else 0

/** The Monday of the ISO week containing this date. */
fun LocalDate.isoWeekStart(): LocalDate = minusDays((dayOfWeek.isoDayNumber - 1).toLong())

/** ISO-8601 week-based year and week number, week starts Monday (`WeekFields.ISO`). */
data class IsoWeek(val weekBasedYear: Int, val week: Int)

fun isoWeekOf(date: LocalDate): IsoWeek {
    // The week belongs to the year of its Thursday; week 1 is the week with the year's first Thursday.
    val thursday = date.isoWeekStart().plusDays(3)
    val firstThursday = LocalDate(thursday.year, 1, 1).let { jan1 ->
        jan1.plusDays(((4 - jan1.dayOfWeek.isoDayNumber + 7) % 7).toLong())
    }
    return IsoWeek(thursday.year, ((thursday.toEpochDays() - firstThursday.toEpochDays()) / 7 + 1).toInt())
}

/** Whole years from [from] to [to] (`java.time.Period.between(from, to).years`). */
fun yearsBetween(from: LocalDate, to: LocalDate): Int {
    var years = to.year - from.year
    if (to.month.number < from.month.number ||
        (to.month == from.month && to.day < from.day)
    ) {
        years--
    }
    return years
}

/** English three-letter month name, as `DateTimeFormatter.ofPattern("MMM", Locale.US)`. */
fun Month.shortName(): String = SHORT_MONTHS[number - 1]

/** "4 Oct" — `ofPattern("d MMM", Locale.US)`. */
fun LocalDate.dayMonthLabel(): String = "$day ${month.shortName()}"

/** "Oct 2026" — `ofPattern("MMM yyyy", Locale.US)`. */
fun LocalDate.monthYearLabel(): String = "${month.shortName()} $year"

/** "20261004" — `DateTimeFormatter.BASIC_ISO_DATE` without an offset (years 0…9999). */
fun LocalDate.basicIsoDate(): String =
    year.toString().padStart(4, '0') + month.number.pad2() + day.pad2()

/** `yyyyMMdd` → date, or `null` when it is not a valid date. */
fun parseBasicIsoDate(text: String): LocalDate? {
    if (text.length != 8 || !text.all { it.isDigit() }) return null
    return runCatching {
        LocalDate(text.substring(0, 4).toInt(), text.substring(4, 6).toInt(), text.substring(6, 8).toInt())
    }.getOrNull()
}

/** `yyyy-MM-dd` → date, or `null` when it is not a valid ISO date. */
fun parseIsoDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

/** All seven days, Monday first (`DayOfWeek.entries` order). */
val ISO_WEEK: List<DayOfWeek> = DayOfWeek.entries

private val SHORT_MONTHS =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
