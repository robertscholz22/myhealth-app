package com.myhealth.ui.common

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * `DateTimeFormatter.ofPattern(pattern, Locale.US)` in common code (P20.3): the UI's fixed English
 * date labels ("d MMM", "EEEE, d MMM yyyy", "MMM d, HH:mm" …). Supported letters: `d dd M MM MMM
 * MMMM yyyy EEE EEEE H HH mm ss`; other letters fail. Any other character is copied.
 * `DateTextTest` checks it against `DateTimeFormatter` on every pattern and many dates.
 */
object UsDateText {

    fun format(date: LocalDate, pattern: String): String = format(LocalDateTime(date.year, date.month, date.day, 0, 0), pattern)

    fun format(dateTime: LocalDateTime, pattern: String): String = buildString {
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (!c.isLetter()) {
                append(c)
                i++
                continue
            }
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            append(field(dateTime, c, n))
            i += n
        }
    }

    private fun field(t: LocalDateTime, letter: Char, count: Int): String = when {
        letter == 'd' && count <= 2 -> t.day.zeroPad(count)
        letter == 'M' && count <= 2 -> t.month.ordinal.plus(1).zeroPad(count)
        letter == 'M' && count == 3 -> MONTHS[t.month.ordinal].take(3)
        letter == 'M' && count == 4 -> MONTHS[t.month.ordinal]
        letter == 'y' && count == 4 -> t.year.zeroPad(4)
        letter == 'E' && count == 3 -> DAYS[t.dayOfWeek.ordinal].take(3)
        letter == 'E' && count == 4 -> DAYS[t.dayOfWeek.ordinal]
        letter == 'H' && count <= 2 -> t.hour.zeroPad(count)
        letter == 'm' && count == 2 -> t.minute.zeroPad(2)
        letter == 's' && count == 2 -> t.second.zeroPad(2)
        else -> throw IllegalArgumentException("unsupported pattern letter ${letter.toString().repeat(count)}")
    }

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
}

/** `format(DateTimeFormatter.ofPattern(pattern, Locale.US))`. */
fun LocalDate.usText(pattern: String): String = UsDateText.format(this, pattern)

fun LocalDateTime.usText(pattern: String): String = UsDateText.format(this, pattern)

/** `String.format("%0${width}d", this)`: the sign counts towards the width, as in Java. */
fun Int.zeroPad(width: Int): String {
    if (this >= 0) return toString().padStart(width, '0')
    val digits = toString().removePrefix("-")
    return "-" + digits.padStart(width - 1, '0')
}

/**
 * [epochMillis] in [zone] with `DateTimeFormatter.ofPattern(pattern)` in the **device locale** —
 * the backup and import timestamps ("4 Okt. 2026, 21:05" on a German phone).
 */
expect fun formatDeviceDateTime(epochMillis: Long, zone: TimeZone, pattern: String): String
