package com.myhealth.domain.util

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import java.time.LocalDate as JavaLocalDate

/**
 * P20.1: the shared [NumberFormat] and date helpers replace `String.format(Locale.US, …)` and
 * `java.time` formatting; every rationale text, goal label and the suggestion-inputs hash depend
 * on them producing exactly what Java did.
 */
class NumberFormatTest {

    private fun javaFixed(value: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", value)

    private fun check(value: Double, decimals: Int) {
        assertWithMessage("$value / $decimals")
            .that(NumberFormat.fixed(value, decimals))
            .isEqualTo(javaFixed(value, decimals))
    }

    @Test
    fun nf01_fixed_matches_java_on_edge_values() {
        val values = listOf(
            0.0, -0.0, 0.05, 0.15, 0.25, 0.35, 0.45, 0.5, 1.5, 2.5, -0.05, -0.04, -1.25, 9.95, 9.96, 99.95,
            0.0001, 0.00049, 0.0005, 1e-7, 123456.789, 1e15, 1.23e20, 47.6519, 4.5, 21097.5, 0.125, 1.005,
            2.675, 1.0 / 3.0, 2.0 / 3.0, 85.0, 100.0, 0.95, 0.995, 0.9995,
        )
        for (v in values) {
            for (d in 0..3) check(v, d)
        }
    }

    @Test
    fun nf02_fixed_matches_java_on_random_values() {
        val random = Random(20)
        repeat(200_000) {
            val magnitude = listOf(1.0, 10.0, 100.0, 1000.0, 100_000.0)[random.nextInt(5)]
            check((random.nextDouble() * 2.0 - 1.0) * magnitude, random.nextInt(4))
        }
        // Values on a decimal rounding boundary (x.xx5) are where half-up and half-even differ.
        repeat(20_000) {
            val v = random.nextInt(-100_000, 100_000) / 1000.0 + 0.0005
            check(v, 3)
            check(v, 2)
        }
    }

    @Test
    fun nf03_signed_hex_and_clock() {
        assertThat(NumberFormat.signed(12)).isEqualTo(String.format(Locale.US, "%+d", 12))
        assertThat(NumberFormat.signed(0)).isEqualTo(String.format(Locale.US, "%+d", 0))
        assertThat(NumberFormat.signed(-3)).isEqualTo(String.format(Locale.US, "%+d", -3))
        assertThat(NumberFormat.signedFixed(9.54, 1)).isEqualTo(String.format(Locale.US, "%+.1f", 9.54))
        assertThat(NumberFormat.signedFixed(-0.04, 1)).isEqualTo(String.format(Locale.US, "%+.1f", -0.04))
        assertThat(NumberFormat.hex(byteArrayOf(0, 15, -1, 16))).isEqualTo("000fff10")
        assertThat(clockLabel(1314)).isEqualTo("21:54")
        assertThat(clockLabel(5100)).isEqualTo("1:25:00")
    }

    @Test
    fun nf04_dates_match_java_time() {
        val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.US)
        val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
        val random = Random(4)
        repeat(5_000) {
            val day = random.nextLong(-30_000, 60_000)
            val jd = JavaLocalDate.ofEpochDay(day)
            val kd = day.epochDayDate()
            assertThat(kd.dayMonthLabel()).isEqualTo(jd.format(dayMonth))
            assertThat(kd.monthYearLabel()).isEqualTo(jd.format(monthYear))
            if (jd.year in 0..9999) {
                assertThat(kd.basicIsoDate()).isEqualTo(jd.format(DateTimeFormatter.BASIC_ISO_DATE))
            }
            assertThat(kd.isoWeekStart().toEpochDays())
                .isEqualTo(jd.minusDays((jd.dayOfWeek.value - 1).toLong()).toEpochDay())
            val other = JavaLocalDate.ofEpochDay(random.nextLong(-30_000, 60_000))
            if (!other.isBefore(jd)) {
                assertThat(yearsBetween(kd, other.toEpochDay().epochDayDate()))
                    .isEqualTo(Period.between(jd, other).years)
            }
        }
    }
}
