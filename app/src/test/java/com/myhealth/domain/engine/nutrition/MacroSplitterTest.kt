package com.myhealth.domain.engine.nutrition

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EngineWarningCode
import org.junit.Test

/** The deterministic macro ordering of PLAN §3.1.5, tested step by step. */
class MacroSplitterTest {

    private fun splitInput(
        targetKcal: Double = 2500.0,
        weightKg: Double = 80.0,
        goalWeightKg: Double? = null,
        goalDeltaKcal: Double = 0.0,
        trainingKcal: Double = 600.0,
        dayType: DayType = DayType.HARD_TRAINING,
        hasStrengthSession: Boolean = false,
        ageYears: Int = 30,
    ) = MacroSplitInput(
        targetKcal = targetKcal,
        weightKg = weightKg,
        goalWeightKg = goalWeightKg,
        goalDeltaKcal = goalDeltaKcal,
        trainingKcal = trainingKcal,
        dayType = dayType,
        hasStrengthSession = hasStrengthSession,
        ageYears = ageYears,
    )

    @Test
    fun ref_weight_blends_a_quarter_of_the_way_to_the_goal_weight() {
        // Above the goal: 70 + 0.25 * 10.
        assertThat(MacroSplitter.refWeightKg(80.0, 70.0)).isWithin(1e-9).of(72.5)
        // At or below the goal, and with no goal at all: the actual weight.
        assertThat(MacroSplitter.refWeightKg(68.0, 70.0)).isWithin(1e-9).of(68.0)
        assertThat(MacroSplitter.refWeightKg(80.0, null)).isWithin(1e-9).of(80.0)
    }

    @Test
    fun protein_bonuses_stack_and_stay_inside_the_allowed_band() {
        val base = MacroSplitter.proteinGramsPerKg(
            splitInput(trainingKcal = 0.0, dayType = DayType.REST),
        )
        assertThat(base.gPerKg).isWithin(1e-9).of(1.6)
        assertThat(base.reasons).isEmpty()

        val all = MacroSplitter.proteinGramsPerKg(
            splitInput(
                goalDeltaKcal = -550.0,
                trainingKcal = 600.0,
                dayType = DayType.HARD_TRAINING,
                hasStrengthSession = true,
                ageYears = 55,
            ),
        )
        // 1.6 + 0.2 deficit + 0.2 hard day + 0.2 strength + 0.1 age, inside [1.4, 2.4].
        assertThat(all.gPerKg).isWithin(1e-9).of(2.3)
        assertThat(all.reasons).containsExactly("deficit", "hard day", "strength", "age 50+").inOrder()
    }

    @Test
    fun carb_floor_is_paid_from_fat_down_to_the_fat_floor() {
        val split = MacroSplitter.split(splitInput())

        // Fat starts at 25 % of 2 500 kcal (69.4 g) and is spent down to 0.8 g/kg = 64 g.
        assertThat(split.fatG).isEqualTo(64.0)
        assertThat(split.targetKcal).isEqualTo(2500.0)
        assertThat(split.warnings.map { it.code }).contains(EngineWarningCode.CLAMPED_TO_FLOOR)
    }

    @Test
    fun carb_priority_day_raises_the_target_by_at_most_ten_percent() {
        val split = MacroSplitter.split(splitInput(dayType = DayType.MATCH_DAY))

        assertThat(split.targetKcal).isEqualTo(2750.0)
        assertThat(split.targetKcal).isAtMost(1.10 * 2500.0)
        assertThat(split.carbsG).isAtLeast(395.0)
    }

    @Test
    fun non_priority_day_never_raises_the_target() {
        val rest = MacroSplitter.split(splitInput(dayType = DayType.REST, trainingKcal = 0.0))
        val hard = MacroSplitter.split(splitInput(dayType = DayType.HARD_TRAINING))

        assertThat(rest.targetKcal).isEqualTo(2500.0)
        assertThat(hard.targetKcal).isEqualTo(2500.0)
        // A rest day's 2.5 g/kg carb floor is easily met, so nothing is clamped.
        assertThat(rest.warnings).isEmpty()
        assertThat(rest.fatShare).isWithin(1e-9).of(0.33)
    }

    @Test
    fun macro_grams_are_rounded_half_up_to_five_five_and_one() {
        val split = MacroSplitter.split(splitInput(dayType = DayType.REST, trainingKcal = 0.0))

        assertThat(split.proteinG % 5.0).isEqualTo(0.0)
        assertThat(split.carbsG % 5.0).isEqualTo(0.0)
        assertThat(split.fatG % 1.0).isEqualTo(0.0)
        // 1.6 g/kg * 80 kg = 128 g, half-up to the nearest 5 g.
        assertThat(split.proteinG).isEqualTo(130.0)
    }

    @Test
    fun protein_is_capped_at_thirty_five_percent_of_energy() {
        val split = MacroSplitter.split(
            splitInput(targetKcal = 1200.0, weightKg = 95.0, dayType = DayType.REST, trainingKcal = 0.0),
        )

        // 1.6 * 95 = 152 g would be 51 % of 1 200 kcal; the cap is 0.35 * 1200 / 4 = 105 g.
        assertThat(split.proteinG).isAtMost(105.0)
    }
}
