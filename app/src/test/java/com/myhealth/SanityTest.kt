package com.myhealth

import com.google.common.truth.Truth.assertThat
import com.myhealth.testutil.Fixtures
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * P0.2 — proves the unit-test tool chain works: JUnit 4 runs, Truth assertions link,
 * and `java.time` is available without desugaring (minSdk 34).
 */
class SanityTest {

    @Test
    fun truth_assertions_are_wired() {
        assertThat(2 + 2).isEqualTo(4)
        assertThat(listOf("a", "b")).containsExactly("a", "b").inOrder()
    }

    @Test
    fun java_time_is_available_without_desugaring() {
        assertThat(LocalDate.of(2026, 9, 12).dayOfWeek).isEqualTo(DayOfWeek.SATURDAY)
    }

    @Test
    fun fixed_clock_pins_today() {
        val clock = Fixtures.fixedClock("2026-09-12T07:30:00Z")
        val today = LocalDate.now(clock.withZone(ZoneId.of("UTC")))
        assertThat(today).isEqualTo(LocalDate.of(2026, 9, 12))
        assertThat(clock.instant().toEpochMilli()).isEqualTo(1789198200000L)
    }
}
