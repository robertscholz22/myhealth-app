package com.myhealth.ui.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.myhealth.domain.util.firstOfMonth
import com.myhealth.domain.util.isoWeekOf
import com.myhealth.domain.util.minusMonths
import com.myhealth.domain.util.plusMonths
import com.myhealth.domain.util.utcDateOfMillis
import com.myhealth.domain.util.utcMidnightMillis
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.Test
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.random.Random

/**
 * P20.3: the common date/number helpers the UI switched to, against the `java.time` /
 * `String.format` / `Math.round` code they replaced.
 */
class DateTextTest {

    /** Every pattern the UI formats with (`usText`, `formatDeviceDateTime` uses java.time on Android). */
    private val patterns = listOf(
        "MMM d, HH:mm", "d MMM", "d/M", "yyyy-MM-dd HH:mm", "d MMM yyyy", "MMMM yyyy", "EEE d MMM",
        "EEEE, d MMM yyyy", "MMM yyyy", "EEEE, MMM d yyyy · HH:mm", "HH:mm", "EEE",
    )

    @Test
    fun dt01_patterns_match_date_time_formatter_us() {
        val random = Random(303)
        repeat(5_000) {
            val day = random.nextLong(-1_000, 80_000)
            val minute = random.nextInt(0, 1440)
            val j = java.time.LocalDate.ofEpochDay(day).atTime(minute / 60, minute % 60)
            val k = LocalDateTime(j.year, j.monthValue, j.dayOfMonth, j.hour, j.minute)
            for (p in patterns) {
                assertWithMessage("%s", "$p $j").that(k.usText(p)).isEqualTo(j.format(DateTimeFormatter.ofPattern(p, Locale.US)))
            }
        }
    }

    @Test
    fun dt02_zero_pad_and_math_round_match_java() {
        for (v in -1_100..1_100) {
            assertThat(v.zeroPad(2)).isEqualTo(String.format(Locale.US, "%02d", v))
        }
        val random = Random(304)
        val specials = listOf(0.5, -0.5, 1.5, -1.5, 2.5, 0.49999999999999994, -0.0, Double.NaN, 1e19, -1e19, Double.POSITIVE_INFINITY)
        (specials + List(50_000) { (random.nextDouble() - 0.5) * 2_000 }).forEach {
            assertWithMessage("%s", it).that(mathRound(it)).isEqualTo(Math.round(it))
        }
    }

    @Test
    fun dt03_iso_week_month_arithmetic_and_picker_millis_match_java_time() {
        for (day in -800L..30_000L step 3) {
            val j = java.time.LocalDate.ofEpochDay(day)
            val k = LocalDate.fromEpochDays(day)
            val week = isoWeekOf(k)
            assertWithMessage("%s", j).that(week.weekBasedYear).isEqualTo(j.get(WeekFields.ISO.weekBasedYear()))
            assertWithMessage("%s", j).that(week.week).isEqualTo(j.get(WeekFields.ISO.weekOfWeekBasedYear()))
            assertThat(k.plusMonths(1).toEpochDays()).isEqualTo(j.plusMonths(1).toEpochDay())
            assertThat(k.minusMonths(13).toEpochDays()).isEqualTo(j.minusMonths(13).toEpochDay())
            assertThat(k.firstOfMonth().toEpochDays()).isEqualTo(j.withDayOfMonth(1).toEpochDay())
            val millis = j.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            assertThat(k.utcMidnightMillis()).isEqualTo(millis)
            assertThat(utcDateOfMillis(millis + 86_399_999).toEpochDays()).isEqualTo(day)
        }
    }
}
