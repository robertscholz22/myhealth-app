package com.myhealth.domain.engine.nutrition

import com.myhealth.domain.engine.nutrition.NutritionDefaults as D
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.DailyHealthSummary
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.Profile
import com.myhealth.domain.util.EngineWarning
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import com.myhealth.domain.util.NumberFormat

/** Engine input (PLAN §3.1). Every value is resolved by the caller — the engine reads no clock. */
data class NutritionTargetInput(
    val date: LocalDate,
    val profile: Profile,
    /** Most recent measurement carrying a weight, within 30 days. */
    val latestWeight: BodyMeasurement?,
    /** Most recent measurement carrying a body-fat percentage, within 30 days. */
    val latestBodyFat: BodyMeasurement?,
    /** Health Connect totals for [date]. */
    val actualDailySummary: DailyHealthSummary?,
    /** Activities already recorded on [date]. */
    val completedSessions: List<ActivitySummary>,
    /** Sessions still planned for [date]. */
    val plannedSessions: List<PlannedSession>,
    /** From [DayTypeResolver]. */
    val dayType: DayType,
    /** `date < today` — only then are the measured Health Connect rungs of the TDEE ladder usable. */
    val isDayComplete: Boolean,
    /**
     * P11.2: where [date] sits in the menstrual cycle, or `null` when cycle tracking is off. It
     * only ever adds a **note** to the explanation — no macro and no calorie target moves, because
     * the owner asked for the information, not for a different target.
     */
    val cycleStatus: CycleStatus? = null,
)

/** Which BMR formula was used (§3.1.1); the label appears in the explanation string. */
enum class BmrMethod(val label: String) {
    MIFFLIN("Mifflin-St Jeor"),
    KATCH_MCARDLE("Katch-McArdle"),
}

/** Which rung of the TDEE ladder was used (§3.1.3); the label appears in the explanation string. */
enum class TdeeSource(val label: String) {
    MEASURED_TOTAL("measured (Health Connect total)"),
    MEASURED_ACTIVE("BMR + measured active kcal"),
    ESTIMATED("estimated (BMR x NEAT + training)"),
}

/**
 * BMR + TDEE for one day — the half of the computation the `inputsHash` of P4.12 needs before it
 * can decide whether a full recompute is due.
 */
data class EnergySummary(
    val weightKg: Double,
    val bmrKcal: Double,
    val tdeeKcal: Double,
    val bmrMethod: BmrMethod,
    val tdeeSource: TdeeSource,
    val training: TrainingEnergy,
    val warnings: List<EngineWarning>,
)

/**
 * The full engine output: the [NutritionTarget] the snapshot stores plus every intermediate the
 * tests of §3.1.8 assert on (unrounded BMR/TDEE, the goal delta, the raw pre-clamp target).
 */
data class NutritionTargetDetail(
    val target: NutritionTarget,
    val energy: EnergySummary,
    val goalDeltaKcal: Double,
    val rawTargetKcal: Double,
    val floorKcal: Double,
    val ceilingKcal: Double,
    val split: MacroSplit,
) {
    val bmrKcal: Double get() = energy.bmrKcal
    val tdeeKcal: Double get() = energy.tdeeKcal
    val trainingKcal: Double get() = energy.training.kcal
    val weightKg: Double get() = energy.weightKg
}

/**
 * The nutrition target engine of PLAN §3.1: BMR ladder → training energy → TDEE ladder → calorie
 * target with its floors and ceilings → [MacroSplitter] → fibre/sugar/fat/salt/water targets →
 * explanation string.
 *
 * Stateless and pure. The [zone] is needed only to turn a [PlannedSession]'s `startMinuteOfDay`
 * into an instant for the planned-vs-completed overlap rule of §3.1.2 — "today" is never read from
 * a clock, it arrives as [NutritionTargetInput.date] / [NutritionTargetInput.isDayComplete].
 */
class NutritionTargetEngine(private val zone: TimeZone) {


    // ---- §3.1.1 + §3.1.2 + §3.1.3 ---------------------------------------------------------------

    fun energySummary(input: NutritionTargetInput): EnergySummary {
        val warnings = mutableListOf<EngineWarning>()
        val weightKg = resolveWeightKg(input, warnings)
        val bodyFatPercent = input.latestBodyFat?.bodyFatPercent
            ?.takeIf { it >= D.BODY_FAT_MIN_PERCENT && it <= D.BODY_FAT_MAX_PERCENT }

        val method = if (bodyFatPercent != null) BmrMethod.KATCH_MCARDLE else BmrMethod.MIFFLIN
        val rawBmr = when (method) {
            BmrMethod.KATCH_MCARDLE -> {
                val lbm = weightKg * (1.0 - bodyFatPercent!! / 100.0)
                D.KATCH_INTERCEPT + D.KATCH_SLOPE * lbm
            }
            BmrMethod.MIFFLIN -> 10.0 * weightKg + 6.25 * input.profile.heightCm -
                5.0 * input.profile.ageYears(input.date) + D.mifflinConstant(input.profile.sex)
        }
        val bmr = D.clamp(rawBmr, D.BMR_MIN_KCAL, D.BMR_MAX_KCAL)
        if (rawBmr < D.BMR_MIN_KCAL) {
            warnings += EngineWarning(
                EngineWarningCode.CLAMPED_TO_FLOOR,
                "BMR ${fmt1(rawBmr)} kcal raised to the ${fmt0(D.BMR_MIN_KCAL)} kcal floor",
            )
        } else if (rawBmr > D.BMR_MAX_KCAL) {
            warnings += EngineWarning(
                EngineWarningCode.CLAMPED_TO_CEILING,
                "BMR ${fmt1(rawBmr)} kcal capped at ${fmt0(D.BMR_MAX_KCAL)} kcal",
            )
        }

        val training = TrainingEnergyCalculator.forDay(
            date = input.date,
            completed = input.completedSessions,
            planned = input.plannedSessions,
            weightKg = weightKg,
            zone = zone,
        )

        val summary = input.actualDailySummary
        val total = summary?.totalEnergyKcal
        val active = summary?.activeEnergyKcal
        val source: TdeeSource
        val rawTdee: Double
        when {
            input.isDayComplete && total != null && total >= D.HC_TOTAL_MIN_BMR_FACTOR * bmr -> {
                source = TdeeSource.MEASURED_TOTAL
                rawTdee = total
            }
            input.isDayComplete && active != null -> {
                source = TdeeSource.MEASURED_ACTIVE
                rawTdee = bmr + active
            }
            else -> {
                source = TdeeSource.ESTIMATED
                rawTdee = bmr * input.profile.neatLevel.factor + training.kcal
            }
        }
        val tdee = D.clamp(rawTdee, bmr, bmr * D.TDEE_MAX_BMR_FACTOR)

        return EnergySummary(
            weightKg = weightKg,
            bmrKcal = bmr,
            tdeeKcal = tdee,
            bmrMethod = method,
            tdeeSource = source,
            training = training,
            warnings = warnings,
        )
    }

    /** Weight ladder of §3.1.1: measurement → profile fallback → goal weight → fail-safe. */
    private fun resolveWeightKg(
        input: NutritionTargetInput,
        warnings: MutableList<EngineWarning>,
    ): Double {
        input.latestWeight?.weightKg?.let { return it }
        val profile = input.profile
        profile.fallbackWeightKg?.let {
            warnings += missingWeight("using the profile's fallback weight ${fmt1(it)} kg")
            return it
        }
        profile.goalWeightKg?.let {
            warnings += missingWeight("using the goal weight ${fmt1(it)} kg")
            return it
        }
        warnings += missingWeight("using the fail-safe ${fmt1(D.FAILSAFE_WEIGHT_KG)} kg")
        return D.FAILSAFE_WEIGHT_KG
    }

    private fun missingWeight(detail: String) =
        EngineWarning(EngineWarningCode.MISSING_WEIGHT, "No weight measured in the last 30 days — $detail.")

    // ---- §3.1.4 .. §3.1.7 -----------------------------------------------------------------------

    fun compute(input: NutritionTargetInput): NutritionTarget = computeDetailed(input).target

    fun computeDetailed(input: NutritionTargetInput): NutritionTargetDetail {
        val energy = energySummary(input)
        val warnings = energy.warnings.toMutableList()
        val profile = input.profile
        val weightKg = energy.weightKg
        val bmr = energy.bmrKcal
        val tdee = energy.tdeeKcal

        val goal = goalDelta(weightKg, profile.goalWeightKg, profile.goalPaceKgPerWeek)
        val deficitRemoved = input.dayType in D.NO_DEFICIT_DAYS && goal.deltaKcal < 0.0
        val deltaKcal = if (deficitRemoved) 0.0 else goal.deltaKcal

        val raw = tdee + deltaKcal
        val floorKcal = maxOf(
            D.TARGET_FLOOR_BMR_FACTOR * bmr,
            D.absoluteFloorKcal(profile.sex),
            bmr + D.TARGET_FLOOR_TRAINING_FACTOR * energy.training.kcal,
            tdee - D.TARGET_FLOOR_BELOW_TDEE,
        )
        val ceilingKcal = minOf(tdee + D.TARGET_CEILING_ABOVE_TDEE, D.TARGET_CEILING_ABSOLUTE)
        val clampedTarget = D.roundTo(D.clamp(raw, floorKcal, ceilingKcal), D.TARGET_STEP_KCAL)
        var clampNote = ""
        if (raw < floorKcal) {
            warnings += EngineWarning(
                EngineWarningCode.CLAMPED_TO_FLOOR,
                "Raised to the safety floor of ${fmt0(floorKcal)} kcal.",
            )
            clampNote = " (raised to the safety floor)"
        } else if (raw > ceilingKcal) {
            warnings += EngineWarning(
                EngineWarningCode.CLAMPED_TO_CEILING,
                "Capped at ${fmt0(ceilingKcal)} kcal.",
            )
            clampNote = " (capped at the surplus ceiling)"
        }

        val split = MacroSplitter.split(
            MacroSplitInput(
                targetKcal = clampedTarget,
                weightKg = weightKg,
                goalWeightKg = profile.goalWeightKg,
                goalDeltaKcal = deltaKcal,
                trainingKcal = energy.training.kcal,
                dayType = input.dayType,
                hasStrengthSession = energy.training.hasStrength,
                ageYears = profile.ageYears(input.date),
            ),
        )
        warnings += split.warnings
        val targetKcal = split.targetKcal
        if (targetKcal > clampedTarget) {
            clampNote += " (+${fmt0(targetKcal - clampedTarget)} kcal for the carb floor)"
        }

        val explanation = explain(
            input = input,
            energy = energy,
            goal = goal,
            deltaKcal = deltaKcal,
            deficitRemoved = deficitRemoved,
            targetKcal = targetKcal,
            clampNote = clampNote,
            split = split,
        )

        val target = NutritionTarget(
            day = input.date.toEpochDays(),
            kcal = asInt(targetKcal),
            proteinG = asInt(split.proteinG),
            carbsG = asInt(split.carbsG),
            fatG = asInt(split.fatG),
            fiberG = asInt(fiberG(targetKcal)),
            sugarCapG = asInt(D.roundTo(D.SUGAR_CAP_ENERGY_SHARE * targetKcal / D.KCAL_PER_G_CARB, 1.0)),
            satFatCapG = asInt(D.roundTo(D.SAT_FAT_CAP_ENERGY_SHARE * targetKcal / D.KCAL_PER_G_FAT, 1.0)),
            saltG = saltG(energy.training),
            waterMl = asInt(waterMl(weightKg, energy.training)),
            bmrKcal = asInt(bmr),
            tdeeKcal = asInt(tdee),
            dayType = input.dayType,
            explanation = explanation,
            warnings = warnings.toList(),
            // The repository stamps these when it stores the snapshot (P4.12).
            inputsHash = "",
            computedAtMillis = 0L,
        )
        return NutritionTargetDetail(
            target = target,
            energy = energy,
            goalDeltaKcal = deltaKcal,
            rawTargetKcal = raw,
            floorKcal = floorKcal,
            ceilingKcal = ceilingKcal,
            split = split,
        )
    }

    /** The goal delta of §3.1.4, plus the label and the pace actually used. */
    data class GoalDelta(val deltaKcal: Double, val paceKgPerWeek: Double, val label: String)

    fun goalDelta(weightKg: Double, goalWeightKg: Double?, goalPaceKgPerWeek: Double): GoalDelta {
        if (goalWeightKg == null) return GoalDelta(0.0, 0.0, "No goal weight")
        if (abs(weightKg - goalWeightKg) <= D.MAINTAIN_BAND_KG) return GoalDelta(0.0, 0.0, "Maintain")
        val losing = goalWeightKg < weightKg
        val cap = if (losing) D.MAX_LOSS_PACE_KG_PER_WEEK else D.MAX_GAIN_PACE_KG_PER_WEEK
        val pace = minOf(abs(goalPaceKgPerWeek), cap)
        val sign = if (losing) -1.0 else 1.0
        return GoalDelta(
            deltaKcal = sign * pace * D.KCAL_PER_KG / D.DAYS_PER_WEEK,
            paceKgPerWeek = pace,
            label = if (losing) "Lose weight" else "Gain weight",
        )
    }

    private fun fiberG(targetKcal: Double): Double = D.roundTo(
        D.clamp(D.FIBER_G_PER_1000_KCAL * targetKcal / 1000.0, D.FIBER_MIN_G, D.FIBER_MAX_G),
        1.0,
    )

    private fun saltG(training: TrainingEnergy): Double {
        val bonus = if (training.durationMin >= D.SALT_TRAINING_MINUTES) D.SALT_TRAINING_BONUS_G else 0.0
        return minOf(D.SALT_BASE_G + bonus, D.SALT_MAX_G)
    }

    private fun waterMl(weightKg: Double, training: TrainingEnergy): Double {
        val raw = D.WATER_ML_PER_KG * weightKg + D.WATER_ML_PER_TRAINING_HOUR * training.durationHours
        return D.clamp(D.roundTo(raw, D.WATER_STEP_ML), D.WATER_MIN_ML, D.WATER_MAX_ML)
    }

    /** The fixed six-line template of §3.1.7. */
    private fun explain(
        input: NutritionTargetInput,
        energy: EnergySummary,
        goal: GoalDelta,
        deltaKcal: Double,
        deficitRemoved: Boolean,
        targetKcal: Double,
        clampNote: String,
        split: MacroSplit,
    ): String {
        val eventSuffix = if (deficitRemoved) " (deficit removed for match/race)" else ""
        val reasons = if (split.proteinReasons.isEmpty()) {
            ""
        } else {
            ", " + split.proteinReasons.joinToString(", ")
        }
        return buildString {
            appendLine(
                "BMR ${fmt0(energy.bmrKcal)} kcal (${energy.bmrMethod.label}, " +
                    "${fmt1(energy.weightKg)} kg, ${fmt1(input.profile.heightCm)} cm, " +
                    "${input.profile.ageYears(input.date)} y)",
            )
            appendLine("TDEE ${fmt0(energy.tdeeKcal)} kcal (${energy.tdeeSource.label})")
            appendLine("Day type: ${input.dayType.name}$eventSuffix")
            appendLine(
                "Goal: ${goal.label} (${fmt1(goal.paceKgPerWeek)} kg/week) -> " +
                    "${signed(deltaKcal)} kcal/day",
            )
            appendLine("Target: ${fmt0(targetKcal)} kcal$clampNote")
            append(
                "Protein ${fmt0(split.proteinG)} g (${fmt1(split.proteinGPerKg)} g/kg$reasons) · " +
                    "Carbs ${fmt0(split.carbsG)} g · Fat ${fmt0(split.fatG)} g " +
                    "(${fmt0(split.fatShare * 100.0)} %)",
            )
            lutealNote(input.cycleStatus)?.let { appendLine(); append(it) }
        }
    }

    /**
     * P11.2's luteal-phase line, appended to the §3.1.7 template. It is the only thing cycle data
     * changes about a nutrition target — the numbers above it are byte-identical either way.
     */
    private fun lutealNote(cycleStatus: CycleStatus?): String? =
        if (cycleStatus?.phase == CyclePhase.LUTEAL) LUTEAL_NOTE else null

    private companion object {
        /** The exact sentence P11.2 specifies. */
        const val LUTEAL_NOTE: String =
            "Luteal phase: appetite and core temperature are typically higher; " +
                "the target is unchanged, listen to hunger."

        fun asInt(value: Double): Int = D.roundTo(value, 1.0).toInt()

        fun fmt0(value: Double): String = asInt(value).toString()

        fun fmt1(value: Double): String = NumberFormat.fixed(value, 1)

        fun signed(value: Double): String = NumberFormat.signed(asInt(value))
    }
}
