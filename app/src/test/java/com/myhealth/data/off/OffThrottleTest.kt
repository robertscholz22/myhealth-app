package com.myhealth.data.off

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The client-side rate limit of PLAN P4.10: at most 15 Open Food Facts product reads a minute.
 * Time is injected, so the refill is asserted rather than slept through.
 */
class OffThrottleTest {

    @Test
    fun throttle01_allows_fifteen_requests_then_blocks() {
        val throttle = OffThrottle(MutableClock(START))

        repeat(15) { index ->
            assertWithMessage("request %s", index + 1).that(throttle.tryAcquire()).isTrue()
        }
        assertThat(throttle.tryAcquire()).isFalse()
        assertThat(throttle.tryAcquire()).isFalse()
    }

    @Test
    fun throttle02_refills_one_token_every_four_seconds() {
        val clock = MutableClock(START)
        val throttle = OffThrottle(clock)
        repeat(15) { throttle.tryAcquire() }

        clock.advance(Duration.ofSeconds(3))
        assertThat(throttle.tryAcquire()).isFalse()

        clock.advance(Duration.ofSeconds(1))
        assertThat(throttle.tryAcquire()).isTrue()
        assertThat(throttle.tryAcquire()).isFalse()
    }

    @Test
    fun throttle03_refills_to_the_full_bucket_after_a_minute_and_no_further() {
        val clock = MutableClock(START)
        val throttle = OffThrottle(clock)
        repeat(15) { throttle.tryAcquire() }

        clock.advance(Duration.ofMinutes(5))

        assertThat(throttle.available()).isWithin(1e-6).of(15.0)
        repeat(15) { assertThat(throttle.tryAcquire()).isTrue() }
        assertThat(throttle.tryAcquire()).isFalse()
    }

    @Test
    fun throttle04_a_stopped_clock_never_refills() {
        val clock = MutableClock(START)
        val throttle = OffThrottle(clock, permitsPerMinute = 2)

        assertThat(throttle.tryAcquire()).isTrue()
        assertThat(throttle.tryAcquire()).isTrue()
        assertThat(throttle.tryAcquire()).isFalse()
        assertThat(throttle.available()).isEqualTo(0.0)

        clock.advance(Duration.ofSeconds(30))
        assertThat(throttle.tryAcquire()).isTrue()
    }

    /** A [Clock] the test moves by hand — `Clock.fixed` cannot advance. */
    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-12T10:00:00Z")
    }
}
