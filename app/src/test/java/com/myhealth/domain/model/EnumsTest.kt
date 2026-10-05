package com.myhealth.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnumsTest {

    @Test
    fun sport_type_group_matches_the_plan_table() {
        val expected = mapOf(
            SportType.SOCCER_MATCH to SportGroup.SOCCER,
            SportType.SOCCER_TRAINING to SportGroup.SOCCER,
            SportType.RUN_OUTDOOR to SportGroup.RUN,
            SportType.RUN_TREADMILL to SportGroup.RUN,
            SportType.RUN_TRACK to SportGroup.RUN,
            SportType.RUN_TRAIL to SportGroup.RUN,
            SportType.STRENGTH to SportGroup.STRENGTH,
            SportType.HIIT to SportGroup.STRENGTH,
            SportType.CYCLING to SportGroup.CYCLE,
            SportType.CYCLING_INDOOR to SportGroup.CYCLE,
            SportType.WALK to SportGroup.WALK,
            SportType.HIKE to SportGroup.WALK,
            SportType.SWIM to SportGroup.SWIM,
            SportType.ROWING to SportGroup.OTHER,
            SportType.MOBILITY to SportGroup.OTHER,
            SportType.OTHER to SportGroup.OTHER,
            SportType.UNKNOWN to SportGroup.OTHER,
        )

        // Fails loudly if a member is added to SportType without updating this test/the table.
        assertThat(expected.keys).containsExactlyElementsIn(SportType.entries)

        expected.forEach { (sport, group) -> assertThat(sport.group).isEqualTo(group) }
    }

    @Test
    fun neat_level_factors_match_the_plan_table() {
        assertThat(NeatLevel.DESK.factor).isEqualTo(1.25)
        assertThat(NeatLevel.LIGHT_ACTIVE.factor).isEqualTo(1.35)
        assertThat(NeatLevel.ACTIVE.factor).isEqualTo(1.45)
        assertThat(NeatLevel.PHYSICAL_JOB.factor).isEqualTo(1.60)
    }
}
