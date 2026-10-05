package com.myhealth.domain.engine.nutrition

import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.Sex
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Every constant and lookup table of the nutrition target engine (PLAN §3.1), in one place so the
 * safety bounds can be reviewed without reading the algorithm around them.
 *
 * Two rounding/clamping conventions are used everywhere below and must not be replaced by their
 * stdlib look-alikes:
 * - [roundTo] is **half-up** (amendment A4). `kotlin.math.round` is half-to-even and would turn
 *   the `nut01` target of 2230 into 2220.
 * - [clamp] is **floor-wins** (amendment A9): `max(floor, min(v, ceil))`. `Double.coerceIn` throws
 *   when `floor > ceil`, which is a legitimate state here (a very low calorie target can push the
 *   fat floor above the fat ceiling), and the safety floor is the bound that must survive.
 */
object NutritionDefaults {

    // ---- BMR (§3.1.1) ------------------------------------------------------------------------

    /** Mifflin–St Jeor sex constant: the `OTHER` value is the mean of the other two. */
    fun mifflinConstant(sex: Sex): Double = when (sex) {
        Sex.MALE -> 5.0
        Sex.FEMALE -> -161.0
        Sex.OTHER -> -78.0
    }

    const val BMR_MIN_KCAL: Double = 1000.0
    const val BMR_MAX_KCAL: Double = 3500.0

    /** Katch–McArdle is only used for a body fat inside this inclusive range. */
    const val BODY_FAT_MIN_PERCENT: Double = 3.0
    const val BODY_FAT_MAX_PERCENT: Double = 60.0
    const val KATCH_INTERCEPT: Double = 370.0
    const val KATCH_SLOPE: Double = 21.6

    /** Look-back for a usable weight / body-fat measurement. */
    const val MEASUREMENT_MAX_AGE_DAYS: Long = 30L

    /** Last rung of the weight ladder — only reached when the profile carries no weight at all. */
    const val FAILSAFE_WEIGHT_KG: Double = 75.0

    // ---- TDEE (§3.1.3) -----------------------------------------------------------------------

    /** A Health Connect daily total below `factor * BMR` is implausible and is ignored. */
    const val HC_TOTAL_MIN_BMR_FACTOR: Double = 0.9
    const val TDEE_MAX_BMR_FACTOR: Double = 3.0

    // ---- calorie target (§3.1.4) --------------------------------------------------------------

    /** 7 700 kcal per kg of body mass, spread over 7 days ⇒ 1 100 kcal/day per kg/week. */
    const val KCAL_PER_KG: Double = 7700.0
    const val DAYS_PER_WEEK: Double = 7.0
    const val MAX_LOSS_PACE_KG_PER_WEEK: Double = 1.0
    const val MAX_GAIN_PACE_KG_PER_WEEK: Double = 0.5

    /** Within this band of the goal weight the goal is "maintain" and the delta is 0. */
    const val MAINTAIN_BAND_KG: Double = 1.0

    const val TARGET_FLOOR_BMR_FACTOR: Double = 1.2
    const val TARGET_FLOOR_TRAINING_FACTOR: Double = 0.5
    const val TARGET_FLOOR_BELOW_TDEE: Double = 1000.0
    const val TARGET_CEILING_ABOVE_TDEE: Double = 700.0
    const val TARGET_CEILING_ABSOLUTE: Double = 5000.0
    const val TARGET_STEP_KCAL: Double = 10.0

    /** Absolute lower bound on the calorie target, by sex. */
    fun absoluteFloorKcal(sex: Sex): Double = if (sex == Sex.FEMALE) 1200.0 else 1500.0

    /** Match/race days never diet: a negative goal delta is removed on these. */
    val NO_DEFICIT_DAYS: Set<DayType> =
        setOf(DayType.MATCH_DAY, DayType.PRE_MATCH, DayType.RACE_DAY, DayType.PRE_RACE)

    // ---- macros (§3.1.5) ----------------------------------------------------------------------

    const val PROTEIN_BASE_G_PER_KG: Double = 1.6
    const val PROTEIN_DEFICIT_BONUS: Double = 0.2
    const val PROTEIN_HARD_DAY_BONUS: Double = 0.2
    const val PROTEIN_STRENGTH_BONUS: Double = 0.2
    const val PROTEIN_AGE_BONUS: Double = 0.1
    const val PROTEIN_AGE_THRESHOLD_YEARS: Int = 50
    const val PROTEIN_HARD_DAY_TRAINING_KCAL: Double = 500.0
    const val PROTEIN_MIN_G_PER_KG: Double = 1.4
    const val PROTEIN_MAX_G_PER_KG: Double = 2.4

    /** Protein never carries more than this share of the day's energy. */
    const val PROTEIN_MAX_ENERGY_SHARE: Double = 0.35

    /** The reference weight blends toward the goal weight when the user is above it. */
    const val REF_WEIGHT_GOAL_BLEND: Double = 0.25

    const val FAT_FLOOR_G_PER_KG: Double = 0.8
    const val FAT_FLOOR_ENERGY_SHARE: Double = 0.20
    const val FAT_CEILING_ENERGY_SHARE: Double = 0.35

    const val KCAL_PER_G_PROTEIN: Double = 4.0
    const val KCAL_PER_G_CARB: Double = 4.0
    const val KCAL_PER_G_FAT: Double = 9.0

    const val PROTEIN_STEP_G: Double = 5.0
    const val CARB_STEP_G: Double = 5.0
    const val FAT_STEP_G: Double = 1.0

    /** On these day types the calorie target may be raised to reach the carb floor. */
    val CARB_PRIORITY_DAYS: Set<DayType> =
        setOf(DayType.PRE_MATCH, DayType.MATCH_DAY, DayType.PRE_RACE, DayType.RACE_DAY)

    /** How much the target may be raised on a carb-priority day (§3.1.5 step 2). */
    const val CARB_PRIORITY_TARGET_RAISE_SHARE: Double = 0.10

    /** Fat share of energy per day type (§3.1.5). */
    val FAT_ENERGY_SHARE: Map<DayType, Double> = mapOf(
        DayType.REST to 0.33,
        DayType.RECOVERY to 0.30,
        DayType.TRAINING to 0.28,
        DayType.HARD_TRAINING to 0.25,
        DayType.MATCH_DAY to 0.22,
        DayType.PRE_MATCH to 0.20,
        DayType.RACE_DAY to 0.20,
        DayType.PRE_RACE to 0.20,
    )

    /** Carbohydrate floor in g per kg of reference weight, per day type (§3.1.5). */
    val CARB_FLOOR_G_PER_KG: Map<DayType, Double> = mapOf(
        DayType.REST to 2.5,
        DayType.RECOVERY to 3.0,
        DayType.TRAINING to 3.5,
        DayType.HARD_TRAINING to 5.0,
        DayType.MATCH_DAY to 6.0,
        DayType.PRE_MATCH to 6.5,
        DayType.RACE_DAY to 6.0,
        DayType.PRE_RACE to 7.0,
    )

    fun fatShareFor(dayType: DayType): Double = FAT_ENERGY_SHARE.getValue(dayType)

    fun carbFloorPerKgFor(dayType: DayType): Double = CARB_FLOOR_G_PER_KG.getValue(dayType)

    // ---- other targets (§3.1.5 "Other targets") -----------------------------------------------

    const val FIBER_G_PER_1000_KCAL: Double = 14.0
    const val FIBER_MIN_G: Double = 25.0
    const val FIBER_MAX_G: Double = 45.0
    const val SUGAR_CAP_ENERGY_SHARE: Double = 0.10
    const val SAT_FAT_CAP_ENERGY_SHARE: Double = 0.10
    const val SALT_BASE_G: Double = 5.0
    const val SALT_TRAINING_BONUS_G: Double = 1.0
    const val SALT_MAX_G: Double = 6.0
    const val SALT_TRAINING_MINUTES: Double = 90.0
    const val WATER_ML_PER_KG: Double = 35.0
    const val WATER_ML_PER_TRAINING_HOUR: Double = 750.0
    const val WATER_MIN_ML: Double = 2000.0
    const val WATER_MAX_ML: Double = 6000.0
    const val WATER_STEP_ML: Double = 100.0

    // ---- DayTypeResolver (§3.1.6) --------------------------------------------------------------

    /** A `date + 1` race counts as a carb-loading day only from this distance up. */
    const val PRE_RACE_MIN_DISTANCE_METERS: Double = 10_000.0
    const val HARD_DAY_TRIMP: Double = 120.0
    const val HARD_DAY_DURATION_MIN: Double = 100.0

    // ---- arithmetic conventions ----------------------------------------------------------------

    /** Half-up rounding to a multiple of [step] (amendment A4). */
    fun roundTo(value: Double, step: Double): Double = floor(value / step + 0.5) * step

    /** Floor-wins clamp (amendment A9): the floor survives even when `floor > ceil`. */
    fun clamp(value: Double, floorValue: Double, ceilValue: Double): Double =
        max(floorValue, min(value, ceilValue))
}
