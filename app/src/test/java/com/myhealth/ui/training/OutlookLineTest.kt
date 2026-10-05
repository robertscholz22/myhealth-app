package com.myhealth.ui.training

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.GoalRules
import com.myhealth.domain.engine.suggest.SuggestFixtures
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import org.junit.Test

/** PLAN §P19 item 8 — the Training header's race-calendar line. */
class OutlookLineTest {

    private fun half(daysOut: Long) = SuggestFixtures.raceGoal(day(daysOut), targetTimeSec = 5100, distanceMeters = 21_097.5)
        .copy(title = "Berlin Half")

    @Test
    fun outlook01_base_names_the_build_start_and_the_race() {
        val outlook = GoalRules.outlook(listOf(half(202)), SuggestFixtures.TODAY_DAY)!!
        assertThat(outlookLine(outlook)).isEqualTo("Base · Build from 17 Jan · Berlin Half on 4 Apr")
    }

    @Test
    fun outlook02_race_week_has_no_next_phase() {
        val outlook = GoalRules.outlook(listOf(half(4)), SuggestFixtures.TODAY_DAY)!!
        assertThat(outlookLine(outlook)).isEqualTo("Race week · Berlin Half on 18 Sep")
    }

    @Test
    fun outlook03_a_deadline_only_has_no_line() {
        assertThat(GoalRules.outlook(listOf(half(202).copy(isRace = false)), SuggestFixtures.TODAY_DAY)).isNull()
    }
}
