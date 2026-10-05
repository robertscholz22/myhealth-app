package com.myhealth.domain.engine.calendar

import com.google.common.truth.Truth.assertThat
import com.myhealth.testutil.Fixtures
import org.junit.Test
import kotlinx.datetime.DayOfWeek

/** Parse/format round-trips for the RFC 5545 subset of PLAN §2.2.4 (P3.1). */
class RecurrenceRuleTest {

    @Test
    fun weekly_with_two_weekdays_round_trips() {
        val rule = RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=TU,TH")

        assertThat(rule).isEqualTo(
            RecurrenceRule(
                freq = RecurrenceFreq.WEEKLY,
                byDay = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
            ),
        )
        assertThat(rule!!.format()).isEqualTo("FREQ=WEEKLY;BYDAY=TU,TH")
        assertThat(RecurrenceRule.parse(rule.format())).isEqualTo(rule)
    }

    @Test
    fun interval_and_until_round_trip() {
        val rule = RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=MO;INTERVAL=2;UNTIL=20261025")!!

        assertThat(rule.interval).isEqualTo(2)
        assertThat(rule.untilDay).isEqualTo(Fixtures.epochDay("2026-10-25"))
        assertThat(rule.format()).isEqualTo("FREQ=WEEKLY;BYDAY=MO;INTERVAL=2;UNTIL=20261025")
        assertThat(RecurrenceRule.parse(rule.format())).isEqualTo(rule)
    }

    @Test
    fun daily_with_count_round_trips_and_tolerates_a_dashed_until() {
        val rule = RecurrenceRule.parse("freq=daily;count=5;until=2026-10-25")!!

        assertThat(rule.freq).isEqualTo(RecurrenceFreq.DAILY)
        assertThat(rule.count).isEqualTo(5)
        assertThat(rule.byDay).isEmpty()
        assertThat(rule.untilDay).isEqualTo(Fixtures.epochDay("2026-10-25"))
        assertThat(rule.format()).isEqualTo("FREQ=DAILY;UNTIL=20261025;COUNT=5")
        assertThat(RecurrenceRule.parse(rule.format())).isEqualTo(rule)
    }

    @Test
    fun unsupported_or_broken_rules_parse_to_null_or_defaults() {
        assertThat(RecurrenceRule.parse(null)).isNull()
        assertThat(RecurrenceRule.parse("   ")).isNull()
        assertThat(RecurrenceRule.parse("FREQ=MONTHLY;BYMONTHDAY=1")).isNull()
        assertThat(RecurrenceRule.parse("BYDAY=MO")).isNull()

        val lenient = RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=XX,MO;INTERVAL=0;UNTIL=nonsense")!!
        assertThat(lenient.byDay).containsExactly(DayOfWeek.MONDAY)
        assertThat(lenient.interval).isEqualTo(1)
        assertThat(lenient.untilDay).isNull()
        assertThat(lenient.count).isNull()
    }
}
