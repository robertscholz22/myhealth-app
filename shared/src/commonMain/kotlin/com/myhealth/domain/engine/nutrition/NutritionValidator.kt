package com.myhealth.domain.engine.nutrition

import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFacts
import com.myhealth.domain.util.EngineWarning
import kotlin.math.abs
import kotlin.math.max

/**
 * The §3.6.1 step-7 plausibility checks, factored out of the label parser so both it (P4.7) and
 * the ingredient editor's live warnings banner (P4.3) run the exact same rules over a
 * [NutritionFacts]. Never blocks a save — every check only ever appends a warning.
 */
object NutritionValidator {

    /** Recognised nutrient fields per §3.6.1 step 7 (energy + the 8 nutrients), for the
     * "fewer than 3 recognised fields" / "missing energy" checks. */
    private fun NutritionFacts.recognisedFieldCount(): Int = listOfNotNull(
        kcal, proteinG, carbsG, sugarG, fatG, satFatG, fiberG, saltG, sodiumG,
    ).size

    /**
     * Runs the step-7 checks over [facts]. [hasEnergyKj] lets a caller that also parsed a kJ
     * value (the label parser, P4.7) suppress the "missing energy" warning when kcal is null but
     * kJ was found — an ingredient draft, which has no kJ field, always passes `false`.
     */
    fun validate(facts: NutritionFacts, hasEnergyKj: Boolean = false): List<EngineWarning> {
        val warnings = mutableListOf<EngineWarning>()

        val fat = facts.fatG
        val satFat = facts.satFatG
        if (fat != null && satFat != null && satFat > fat + 0.1) {
            warnings += EngineWarning(
                EngineWarningCode.IMPLAUSIBLE_VALUE,
                "Saturated fat (${satFat}g) is greater than total fat (${fat}g).",
            )
        }

        val carbs = facts.carbsG
        val sugar = facts.sugarG
        if (carbs != null && sugar != null && sugar > carbs + 0.1) {
            warnings += EngineWarning(
                EngineWarningCode.IMPLAUSIBLE_VALUE,
                "Sugar (${sugar}g) is greater than total carbohydrate (${carbs}g).",
            )
        }

        if (facts.basis == MeasureBasis.PER_100G || facts.basis == MeasureBasis.PER_100ML) {
            val per100 = listOf(
                "protein" to facts.proteinG,
                "carbohydrate" to facts.carbsG,
                "sugar" to facts.sugarG,
                "fat" to facts.fatG,
                "saturated fat" to facts.satFatG,
                "fiber" to facts.fiberG,
                "salt" to facts.saltG,
            )
            per100.forEach { (label, value) ->
                if (value != null && value > 100.0) {
                    warnings += EngineWarning(
                        EngineWarningCode.IMPLAUSIBLE_VALUE,
                        "$label (${value}g) is more than 100 g per 100 g/ml.",
                    )
                }
            }
        }

        val kcal = facts.kcal
        if (kcal != null) {
            val protein = facts.proteinG ?: 0.0
            val carbsForAtwater = facts.carbsG ?: 0.0
            val fatForAtwater = facts.fatG ?: 0.0
            val fiber = facts.fiberG ?: 0.0
            val computed = 4 * protein + 4 * carbsForAtwater + 9 * fatForAtwater + 2 * fiber
            if (abs(computed - kcal) > max(30.0, 0.25 * kcal)) {
                warnings += EngineWarning(
                    EngineWarningCode.ENERGY_MISMATCH,
                    "Energy from macros ($computed kcal) does not match the stated $kcal kcal.",
                )
            }
        } else if (!hasEnergyKj) {
            warnings += EngineWarning(EngineWarningCode.LOW_CONFIDENCE, "No energy value was found.")
        }

        if (facts.recognisedFieldCount() < 3) {
            warnings += EngineWarning(EngineWarningCode.LOW_CONFIDENCE, "Fewer than 3 nutrient fields were recognised.")
        }

        return warnings
    }
}
