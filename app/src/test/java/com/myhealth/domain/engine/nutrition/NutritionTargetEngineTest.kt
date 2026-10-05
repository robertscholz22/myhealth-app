package com.myhealth.domain.engine.nutrition

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.nutrition.NutritionFixtures.activity
import com.myhealth.domain.engine.nutrition.NutritionFixtures.engine
import com.myhealth.domain.engine.nutrition.NutritionFixtures.input
import com.myhealth.domain.engine.nutrition.NutritionFixtures.planned
import com.myhealth.domain.engine.nutrition.NutritionFixtures.profile
import com.myhealth.domain.engine.nutrition.NutritionFixtures.summary
import com.myhealth.domain.engine.nutrition.NutritionFixtures.weight
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SportType
import org.junit.Test
import kotlin.math.abs

/**
 * The 20 named cases of PLAN §3.1.8, plus `nut21` from §5 P11.2. Every function name is the case ID from the plan; the
 * reference athlete is male / 30 y / 180 cm / 80 kg / `DESK` NEAT on a `REST` day with no Health
 * Connect data, which is `nut01`.
 */
class NutritionTargetEngineTest {

    private val engine = engine()

    @Test
    fun nut01_male30y_180cm_80kg_maintain_restday() {
        val detail = engine.computeDetailed(input())

        assertThat(detail.bmrKcal).isWithin(0.01).of(1780.0)
        assertThat(detail.tdeeKcal).isWithin(0.01).of(2225.0)
        assertThat(detail.target.kcal).isWithin(10).of(2230)
        assertThat(detail.target.proteinG).isEqualTo(130)
        assertThat(detail.goalDeltaKcal).isEqualTo(0.0)
    }

    @Test
    fun nut02_female_uses_minus161_constant() {
        val detail = engine.computeDetailed(input(profile = profile(sex = Sex.FEMALE)))

        assertThat(detail.bmrKcal).isWithin(0.01).of(1614.0)
    }

    @Test
    fun nut03_other_sex_uses_minus78_constant() {
        val detail = engine.computeDetailed(input(profile = profile(sex = Sex.OTHER)))

        assertThat(detail.bmrKcal).isWithin(0.01).of(1697.0)
    }

    @Test
    fun nut04_katch_mcardle_used_when_bodyfat_present() {
        val bodyFat = weight(weightKg = 80.0, bodyFatPercent = 15.0)
        val detail = engine.computeDetailed(input(latestBodyFat = bodyFat))

        // LBM = 80 * 0.85 = 68 kg; BMR = 370 + 21.6 * 68.
        assertThat(detail.bmrKcal).isWithin(0.01).of(1838.8)
        assertThat(detail.energy.bmrMethod).isEqualTo(BmrMethod.KATCH_MCARDLE)
        assertThat(detail.target.explanation).contains("Katch-McArdle")
    }

    @Test
    fun nut05_bodyfat_out_of_range_falls_back_to_mifflin() {
        val bodyFat = weight(weightKg = 80.0, bodyFatPercent = 1.0)
        val detail = engine.computeDetailed(input(latestBodyFat = bodyFat))

        assertThat(detail.energy.bmrMethod).isEqualTo(BmrMethod.MIFFLIN)
        assertThat(detail.bmrKcal).isWithin(0.01).of(1780.0)
        assertThat(detail.target.explanation).contains("Mifflin-St Jeor")
    }

    @Test
    fun nut06_deficit_pace_0_5_reduces_target_by_550() {
        val detail = engine.computeDetailed(
            input(profile = profile(goalWeightKg = 70.0, goalPaceKgPerWeek = -0.5)),
        )

        assertThat(detail.goalDeltaKcal).isWithin(0.001).of(-550.0)
        assertThat(detail.rawTargetKcal).isWithin(0.001).of(2225.0 - 550.0)
    }

    @Test
    fun nut07_aggressive_deficit_clamped_by_bmr_floor() {
        val detail = engine.computeDetailed(
            input(profile = profile(goalWeightKg = 70.0, goalPaceKgPerWeek = -1.0)),
        )

        // 1.2 * 1780 = 2136, rounded half-up to a multiple of 10.
        assertThat(detail.target.kcal).isEqualTo(2140)
        assertThat(detail.goalDeltaKcal).isWithin(0.001).of(-1100.0)
        assertThat(detail.target.warnings.map { it.code }).contains(EngineWarningCode.CLAMPED_TO_FLOOR)
    }

    @Test
    fun nut08_surplus_capped_at_plus_700() {
        val detail = engine.computeDetailed(
            input(
                profile = profile(goalWeightKg = 90.0, goalPaceKgPerWeek = 1.0),
                summary = summary(totalEnergyKcal = 4600.0),
                isDayComplete = true,
            ),
        )

        // Gain pace is capped at 0.5 kg/week (+550 kcal), and the absolute 5 000 kcal ceiling bites.
        assertThat(detail.goalDeltaKcal).isWithin(0.001).of(550.0)
        assertThat(detail.target.kcal.toDouble()).isAtMost(detail.tdeeKcal + 700.0)
        assertThat(detail.target.kcal).isEqualTo(5000)
        assertThat(detail.target.warnings.map { it.code }).contains(EngineWarningCode.CLAMPED_TO_CEILING)
    }

    @Test
    fun nut09_match_day_removes_deficit() {
        val detail = engine.computeDetailed(
            input(
                profile = profile(goalWeightKg = 70.0, goalPaceKgPerWeek = -0.5),
                dayType = DayType.MATCH_DAY,
            ),
        )

        assertThat(detail.goalDeltaKcal).isEqualTo(0.0)
        assertThat(detail.target.kcal).isAtLeast(detail.target.tdeeKcal)
        assertThat(detail.target.explanation).contains("deficit removed for match/race")
    }

    @Test
    fun nut10_pre_match_carb_load_raises_carbs_and_lowers_fat() {
        val detail = engine.computeDetailed(
            input(
                profile = profile(neatLevel = NeatLevel.PHYSICAL_JOB, goalWeightKg = null),
                planned = listOf(
                    planned(
                        sportType = SportType.SOCCER_TRAINING,
                        sessionType = SessionType.SOCCER_TRAINING,
                        targetDurationMin = 90,
                    ),
                ),
                dayType = DayType.PRE_MATCH,
            ),
        )

        // 6.5 g/kg of the 80 kg reference weight.
        assertThat(detail.target.carbsG).isAtLeast(520)
        // Fat sits on its 20 %-of-energy floor.
        val fatShareOfEnergy = 9.0 * detail.target.fatG / detail.target.kcal
        assertThat(fatShareOfEnergy).isWithin(0.01).of(0.20)
    }

    @Test
    fun nut11_hard_training_day_protein_at_least_2_0_g_per_kg() {
        val detail = engine.computeDetailed(
            input(
                profile = profile(goalWeightKg = 70.0, goalPaceKgPerWeek = -0.5),
                completed = listOf(
                    activity(sportType = SportType.STRENGTH, durationMin = 60, activeEnergyKcal = 600.0),
                ),
                dayType = DayType.TRAINING,
            ),
        )

        // 1.6 base + 0.2 deficit + 0.2 training ≥ 500 kcal + 0.2 strength.
        assertThat(detail.split.proteinGPerKg).isWithin(0.001).of(2.2)
        assertThat(detail.target.explanation).contains("2.2 g/kg")
        assertThat(detail.target.proteinG).isEqualTo(160)
    }

    @Test
    fun nut12_protein_capped_at_35_percent_of_energy() {
        val detail = engine.computeDetailed(
            input(
                profile = profile(
                    sex = Sex.FEMALE,
                    ageYears = 60,
                    heightCm = 150.0,
                    goalWeightKg = null,
                ),
                completed = listOf(
                    activity(sportType = SportType.STRENGTH, durationMin = 45, activeEnergyKcal = 195.0),
                ),
                dayType = DayType.HARD_TRAINING,
            ),
        )

        // 2.1 g/kg of 80 kg = 168 g would be 37 % of the 1 790 kcal target.
        assertThat(detail.split.proteinGPerKg).isWithin(0.001).of(2.1)
        assertThat(4.0 * detail.target.proteinG).isAtMost(0.35 * detail.target.kcal)
        assertThat(detail.target.proteinG).isLessThan(168)
    }

    @Test
    fun nut13_macros_sum_within_30_kcal_of_target() {
        val dayTypes = listOf(DayType.REST, DayType.TRAINING, DayType.HARD_TRAINING, DayType.PRE_MATCH)
        val goals = listOf<Pair<Double?, Double>>(80.0 to 0.0, 70.0 to -0.5)
        var cases = 0

        for (sex in Sex.entries) {
            for (dayType in dayTypes) {
                for ((goalWeight, pace) in goals) {
                    val target = engine.compute(
                        input(
                            profile = profile(
                                sex = sex,
                                goalWeightKg = goalWeight,
                                goalPaceKgPerWeek = pace,
                            ),
                            dayType = dayType,
                        ),
                    )
                    val macroKcal = 4.0 * target.proteinG + 4.0 * target.carbsG + 9.0 * target.fatG
                    assertThat(abs(macroKcal - target.kcal)).isAtMost(30.0)
                    assertThat(target.carbsG).isGreaterThan(0)
                    cases++
                }
            }
        }
        assertThat(cases).isEqualTo(24)
    }

    @Test
    fun nut14_missing_weight_uses_fallback_and_warns() {
        val detail = engine.computeDetailed(
            input(profile = profile(fallbackWeightKg = 78.0), latestWeight = null),
        )

        assertThat(detail.weightKg).isEqualTo(78.0)
        assertThat(detail.bmrKcal).isWithin(0.01).of(1760.0)
        assertThat(detail.target.warnings.map { it.code }).contains(EngineWarningCode.MISSING_WEIGHT)
    }

    @Test
    fun nut15_tdee_prefers_health_connect_total_on_complete_day() {
        val detail = engine.computeDetailed(
            input(summary = summary(totalEnergyKcal = 3100.0), isDayComplete = true),
        )

        assertThat(detail.tdeeKcal).isWithin(0.01).of(3100.0)
        assertThat(detail.energy.tdeeSource).isEqualTo(TdeeSource.MEASURED_TOTAL)
        assertThat(detail.target.explanation).contains("measured (Health Connect total)")
    }

    @Test
    fun nut16_tdee_ignores_implausibly_low_hc_total() {
        val detail = engine.computeDetailed(
            input(
                summary = summary(totalEnergyKcal = 900.0, activeEnergyKcal = 500.0),
                isDayComplete = true,
            ),
        )

        // 900 < 0.9 * 1780, so rung 1 is skipped and rung 2 (BMR + active) is used.
        assertThat(detail.energy.tdeeSource).isEqualTo(TdeeSource.MEASURED_ACTIVE)
        assertThat(detail.tdeeKcal).isWithin(0.01).of(2280.0)
    }

    @Test
    fun nut17_met_estimate_for_run_matches_acsm() {
        assertThat(MetTable.runMet(10.0)).isWithin(1e-9).of(10.52)

        val detail = engine.computeDetailed(
            input(
                completed = listOf(
                    activity(
                        sportType = SportType.RUN_OUTDOOR,
                        durationMin = 60,
                        avgSpeedMps = 10.0 / 3.6,
                    ),
                ),
                dayType = DayType.TRAINING,
            ),
        )

        // (10.52 - 1) * 80 kg * 1 h.
        assertThat(detail.trainingKcal).isWithin(1.0).of(761.6)
    }

    @Test
    fun nut18_fiber_sugar_salt_water_bounds() {
        val detail = engine.computeDetailed(
            input(
                completed = listOf(
                    activity(sportType = SportType.CYCLING, durationMin = 90, activeEnergyKcal = 600.0),
                ),
                dayType = DayType.TRAINING,
            ),
        )
        val target = detail.target

        assertThat(target.saltG).isEqualTo(6.0)
        assertThat(target.waterMl).isAtLeast(2000)
        assertThat(target.waterMl).isAtMost(6000)
        assertThat(target.waterMl % 100).isEqualTo(0)
        assertThat(target.waterMl).isEqualTo(3900)
        assertThat(target.fiberG).isAtLeast(25)
        assertThat(target.fiberG).isAtMost(45)
        assertThat(target.sugarCapG.toDouble()).isWithin(1.0).of(0.10 * target.kcal / 4.0)
        assertThat(target.satFatCapG.toDouble()).isWithin(1.0).of(0.10 * target.kcal / 9.0)
    }

    @Test
    fun nut19_explanation_contains_bmr_tdee_daytype_and_goal() {
        val explanation = engine.compute(
            input(profile = profile(goalWeightKg = 70.0, goalPaceKgPerWeek = -0.5)),
        ).explanation

        assertThat(explanation).contains("BMR 1780 kcal (Mifflin-St Jeor, 80.0 kg, 180.0 cm, 30 y)")
        assertThat(explanation).contains("TDEE 2225 kcal (estimated (BMR x NEAT + training))")
        assertThat(explanation).contains("Day type: REST")
        assertThat(explanation).contains("Goal: Lose weight (0.5 kg/week) -> -550 kcal/day")
        assertThat(explanation).contains("Target: ")
        assertThat(explanation).contains("Protein ")
        assertThat(explanation).contains("g/kg")
        assertThat(explanation).contains("Carbs ")
        assertThat(explanation).contains("Fat ")
    }

    @Test
    fun nut20_rest_day_fat_share_is_33_percent() {
        val target = engine.compute(input(dayType = DayType.REST))

        val share = 9.0 * target.fatG / target.kcal
        assertThat(share).isAtLeast(0.30)
        assertThat(share).isAtMost(0.35)
    }

    /**
     * P11.2: the luteal-phase note is appended to the §3.1.7 explanation and **nothing else moves**
     * — not one kcal, not one gram. The owner asked to be told, not to be fed differently.
     */
    @Test
    fun nut21_luteal_note_added_without_changing_target() {
        val luteal = NutritionFixtures.cycleStatus(offset = 20)
        assertThat(luteal.phase).isEqualTo(CyclePhase.LUTEAL)

        val plain = engine.compute(input())
        val withCycle = engine.compute(input(cycleStatus = luteal))

        assertThat(withCycle.explanation).contains(
            "Luteal phase: appetite and core temperature are typically higher; " +
                "the target is unchanged, listen to hunger.",
        )
        assertThat(plain.explanation).doesNotContain("Luteal phase")
        assertThat(withCycle.explanation.lineSequence().count())
            .isEqualTo(plain.explanation.lineSequence().count() + 1)
        assertThat(withCycle.copy(explanation = plain.explanation)).isEqualTo(plain)

        // Every other phase leaves the explanation exactly as it was.
        val follicular = NutritionFixtures.cycleStatus(offset = 7)
        assertThat(follicular.phase).isEqualTo(CyclePhase.FOLLICULAR)
        assertThat(engine.compute(input(cycleStatus = follicular))).isEqualTo(plain)
    }
}
