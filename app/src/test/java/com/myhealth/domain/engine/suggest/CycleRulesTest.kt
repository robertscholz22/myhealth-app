package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import org.junit.Test

/**
 * The four rules of PLAN §5 P11.2 in isolation — the caps, the `recoveryFit` factor, the `+0.10`
 * nudge and the pro-rata weekly-target reduction that [SuggestionEngineCycleTest] then asserts on
 * whole generated weeks.
 */
class CycleRulesTest {

    private fun statuses(vararg days: Pair<Long, CycleStatus>): Map<Long, CycleStatus> = days.toMap()

    private fun statusOn(day: Long, offset: Long): CycleStatus =
        SuggestFixtures.cycleStatuses(offset = offset, horizonDays = 28).getValue(day)

    @Test
    fun early_menstrual_caps_intensity_at_moderate() {
        val dayOne = statusOn(SuggestFixtures.day(0), offset = 0)
        val dayThree = statusOn(SuggestFixtures.day(2), offset = 0)

        assertThat(CycleRules.violatesEarlyMenstrualCap(dayOne, Intensity.HIGH)).isTrue()
        assertThat(CycleRules.violatesEarlyMenstrualCap(dayOne, Intensity.MAX)).isTrue()
        assertThat(CycleRules.violatesEarlyMenstrualCap(dayOne, Intensity.MODERATE)).isFalse()
        // Day 3 onwards hard work is allowed again.
        assertThat(CycleRules.violatesEarlyMenstrualCap(dayThree, Intensity.HIGH)).isFalse()
        // No cycle data means no rule at all.
        assertThat(CycleRules.violatesEarlyMenstrualCap(null, Intensity.MAX)).isFalse()
    }

    @Test
    fun later_menstrual_days_only_discount_recovery_fit() {
        val dayOne = statusOn(SuggestFixtures.day(0), offset = 0)
        val dayFour = statusOn(SuggestFixtures.day(3), offset = 0)
        val follicular = statusOn(SuggestFixtures.day(6), offset = 0)

        assertThat(CycleRules.recoveryFactor(dayFour, Intensity.HIGH))
            .isWithin(1e-9).of(CycleRules.MENSTRUAL_HARD_RECOVERY_FACTOR)
        assertThat(CycleRules.recoveryFactor(dayFour, Intensity.LOW)).isWithin(1e-9).of(1.0)
        // Days 1–2 are capped outright, so the discount never applies there.
        assertThat(CycleRules.recoveryFactor(dayOne, Intensity.HIGH)).isWithin(1e-9).of(1.0)
        assertThat(CycleRules.recoveryFactor(follicular, Intensity.HIGH)).isWithin(1e-9).of(1.0)
        assertThat(CycleRules.recoveryFactor(null, Intensity.HIGH)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun the_phase_bonus_rewards_the_work_each_phase_suits() {
        val follicular = statusOn(SuggestFixtures.day(6), offset = 0)
        val lateLuteal = statusOn(SuggestFixtures.day(0), offset = -23)

        fun bonus(status: CycleStatus, type: SessionType, group: SportGroup, intensity: Intensity) =
            CycleRules.scoreBonus(status, type, group, intensity)

        assertThat(bonus(follicular, SessionType.INTERVAL_RUN, SportGroup.RUN, Intensity.HIGH))
            .isWithin(1e-9).of(CycleRules.PHASE_SCORE_BONUS)
        assertThat(bonus(follicular, SessionType.STRENGTH_UPPER, SportGroup.STRENGTH, Intensity.MODERATE))
            .isWithin(1e-9).of(CycleRules.PHASE_SCORE_BONUS)
        assertThat(bonus(follicular, SessionType.EASY_RUN, SportGroup.RUN, Intensity.LOW))
            .isWithin(1e-9).of(0.0)

        assertThat(lateLuteal.isLateLuteal).isTrue()
        assertThat(bonus(lateLuteal, SessionType.RECOVERY_RUN, SportGroup.RUN, Intensity.RECOVERY))
            .isWithin(1e-9).of(CycleRules.PHASE_SCORE_BONUS)
        assertThat(bonus(lateLuteal, SessionType.MOBILITY, SportGroup.OTHER, Intensity.RECOVERY))
            .isWithin(1e-9).of(CycleRules.PHASE_SCORE_BONUS)
        assertThat(bonus(lateLuteal, SessionType.INTERVAL_RUN, SportGroup.RUN, Intensity.HIGH))
            .isWithin(1e-9).of(0.0)
    }

    @Test
    fun the_weekly_target_factor_is_pro_rata_over_the_late_luteal_days() {
        val allLate = SuggestFixtures.cycleStatuses(offset = -23, horizonDays = 5)
        assertThat(CycleRules.weeklyTargetFactor(allLate, horizonDays = 5))
            .isWithin(1e-9).of(CycleRules.LATE_LUTEAL_TARGET_FACTOR)

        // Five of seven horizon days inside the window: 1 - 0.10 * 5/7.
        val partly = SuggestFixtures.cycleStatuses(offset = -23, horizonDays = 7)
        assertThat(CycleRules.lateLutealDays(partly)).hasSize(5)
        assertThat(CycleRules.weeklyTargetFactor(partly, horizonDays = 7))
            .isWithin(1e-9).of(1.0 - 0.10 * 5.0 / 7.0)

        // No cycle data, or a horizon clear of the window: the budget is untouched.
        assertThat(CycleRules.weeklyTargetFactor(statuses(), horizonDays = 7)).isWithin(1e-9).of(1.0)
        val follicularWeek = SuggestFixtures.cycleStatuses(offset = -5)
        assertThat(CycleRules.weeklyTargetFactor(follicularWeek, horizonDays = 7)).isWithin(1e-9).of(1.0)
    }
}
