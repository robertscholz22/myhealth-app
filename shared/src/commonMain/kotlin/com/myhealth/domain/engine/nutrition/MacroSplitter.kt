package com.myhealth.domain.engine.nutrition

import com.myhealth.domain.engine.nutrition.NutritionDefaults as D
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.util.EngineWarning

/** Everything the macro split of PLAN §3.1.5 depends on, and nothing else. */
data class MacroSplitInput(
    /** The calorie target from §3.1.4 — the splitter may raise it on a carb-priority day. */
    val targetKcal: Double,
    val weightKg: Double,
    val goalWeightKg: Double?,
    /** The goal delta *after* the match-day override, so a match day gets no deficit bonus. */
    val goalDeltaKcal: Double,
    val trainingKcal: Double,
    val dayType: DayType,
    val hasStrengthSession: Boolean,
    val ageYears: Int,
)

/**
 * Result of the split. [targetKcal] is the possibly-raised target (§3.1.5 step 2) and is what the
 * snapshot stores; the gram values are already rounded (protein/carbs to 5 g, fat to 1 g).
 */
data class MacroSplit(
    val targetKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val proteinGPerKg: Double,
    val fatShare: Double,
    val refWeightKg: Double,
    /** Short labels for the protein bonuses that fired, for the explanation string (§3.1.7). */
    val proteinReasons: List<String>,
    val warnings: List<EngineWarning> = emptyList(),
)

/**
 * The deterministic macro splitter of PLAN §3.1.5. The ordering is normative — protein first (it
 * has a hard cap), then fat (clamped between its floor and ceiling), then carbohydrate as the
 * remainder, then the two-step rebalance that pays for the carb floor out of fat and, on
 * carb-priority days only, out of a ≤ 10 % raise of the calorie target.
 *
 * Post-condition (asserted by `nut13`): `|4*protein + 4*carbs + 9*fat - target| <= 30`, the
 * residue of rounding protein/carbs to 5 g, fat to 1 g and the raised target to 10 kcal.
 */
object MacroSplitter {

    /** Reference weight for the protein and fat floors (§3.1.5). */
    fun refWeightKg(weightKg: Double, goalWeightKg: Double?): Double {
        if (goalWeightKg == null) return weightKg
        if (weightKg <= goalWeightKg) return weightKg
        return goalWeightKg + D.REF_WEIGHT_GOAL_BLEND * (weightKg - goalWeightKg)
    }

    fun split(input: MacroSplitInput): MacroSplit {
        val warnings = mutableListOf<EngineWarning>()
        var target = input.targetKcal
        val refWeight = refWeightKg(input.weightKg, input.goalWeightKg)

        val protein = proteinGramsPerKg(input)
        var proteinG = refWeight * protein.gPerKg
        val proteinCapG = D.PROTEIN_MAX_ENERGY_SHARE * target / D.KCAL_PER_G_PROTEIN
        if (proteinG > proteinCapG) proteinG = proteinCapG

        val fatShare = D.fatShareFor(input.dayType)
        val fatFloorG = maxOf(
            D.FAT_FLOOR_G_PER_KG * refWeight,
            D.FAT_FLOOR_ENERGY_SHARE * target / D.KCAL_PER_G_FAT,
        )
        val fatCeilG = D.FAT_CEILING_ENERGY_SHARE * target / D.KCAL_PER_G_FAT
        var fatG = D.clamp(fatShare * target / D.KCAL_PER_G_FAT, fatFloorG, fatCeilG)

        var carbG = (target - D.KCAL_PER_G_PROTEIN * proteinG - D.KCAL_PER_G_FAT * fatG) / D.KCAL_PER_G_CARB
        val carbFloorG = D.carbFloorPerKgFor(input.dayType) * refWeight

        if (carbG < carbFloorG) {
            // 1) take from fat, down to its floor.
            val needKcal = (carbFloorG - carbG) * D.KCAL_PER_G_CARB
            val availKcal = (fatG - fatFloorG) * D.KCAL_PER_G_FAT
            val take = minOf(needKcal, maxOf(0.0, availKcal))
            fatG -= take / D.KCAL_PER_G_FAT
            carbG += take / D.KCAL_PER_G_CARB

            // 2) on carb-priority days only, buy the rest with up to 10 % more energy.
            if (carbG < carbFloorG && input.dayType in D.CARB_PRIORITY_DAYS) {
                val extra = minOf(
                    (carbFloorG - carbG) * D.KCAL_PER_G_CARB,
                    D.CARB_PRIORITY_TARGET_RAISE_SHARE * target,
                )
                target += extra
                carbG += extra / D.KCAL_PER_G_CARB
                target = D.roundTo(target, D.TARGET_STEP_KCAL)
            }
            if (carbG < carbFloorG) {
                warnings += EngineWarning(
                    EngineWarningCode.CLAMPED_TO_FLOOR,
                    "carb floor not reachable within calorie target",
                )
            }
        }
        if (carbG < 0.0) {
            // Only reachable when the protein cap and the fat floor together exceed the target,
            // i.e. a tiny target against a heavy reference weight. §3.1.5 does not name this case;
            // negative carbohydrate is never a usable answer, so it is floored at zero and flagged.
            warnings += EngineWarning(
                EngineWarningCode.IMPLAUSIBLE_VALUE,
                "protein and fat floors exceed the calorie target; carbohydrate set to 0 g",
            )
            carbG = 0.0
        }

        return MacroSplit(
            targetKcal = target,
            proteinG = D.roundTo(proteinG, D.PROTEIN_STEP_G),
            carbsG = D.roundTo(carbG, D.CARB_STEP_G),
            fatG = D.roundTo(fatG, D.FAT_STEP_G),
            proteinGPerKg = protein.gPerKg,
            fatShare = fatShare,
            refWeightKg = refWeight,
            proteinReasons = protein.reasons,
            warnings = warnings,
        )
    }

    /** The protein ladder of §3.1.5, plus the labels of the bonuses that fired. */
    fun proteinGramsPerKg(input: MacroSplitInput): ProteinPerKg {
        val reasons = mutableListOf<String>()
        var p = D.PROTEIN_BASE_G_PER_KG
        if (input.goalDeltaKcal < 0.0) {
            p += D.PROTEIN_DEFICIT_BONUS
            reasons += "deficit"
        }
        val hardDay = input.trainingKcal >= D.PROTEIN_HARD_DAY_TRAINING_KCAL ||
            input.dayType in setOf(DayType.HARD_TRAINING, DayType.MATCH_DAY, DayType.RACE_DAY)
        if (hardDay) {
            p += D.PROTEIN_HARD_DAY_BONUS
            reasons += "hard day"
        }
        if (input.hasStrengthSession) {
            p += D.PROTEIN_STRENGTH_BONUS
            reasons += "strength"
        }
        if (input.ageYears >= D.PROTEIN_AGE_THRESHOLD_YEARS) {
            p += D.PROTEIN_AGE_BONUS
            reasons += "age ${D.PROTEIN_AGE_THRESHOLD_YEARS}+"
        }
        return ProteinPerKg(
            gPerKg = D.clamp(p, D.PROTEIN_MIN_G_PER_KG, D.PROTEIN_MAX_G_PER_KG),
            reasons = reasons,
        )
    }

    data class ProteinPerKg(val gPerKg: Double, val reasons: List<String>)
}
