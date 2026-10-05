package com.myhealth.domain.engine.label

import com.myhealth.domain.engine.nutrition.NutritionValidator
import com.myhealth.domain.model.NutritionFacts
import com.myhealth.domain.model.NutritionFactsDraft
import com.myhealth.domain.util.EngineWarning

/**
 * Step 7 of §3.6.1 for a parsed draft. The rules themselves live in
 * [NutritionValidator] (P4.1) so the label parser and the ingredient editor cannot drift apart;
 * this object only adapts a [NutritionFactsDraft] onto them and answers the
 * "zero recognised fields ⇒ `Failed(NO_NUTRIENTS_FOUND)`" question for the parser.
 */
object LabelValidator {

    /** The draft's parsed values as confirmed-shaped facts, for the shared step-7 rules. */
    fun toFacts(draft: NutritionFactsDraft): NutritionFacts = NutritionFacts(
        basis = draft.basis,
        kcal = draft.energyKcal.value,
        proteinG = draft.proteinG.value,
        carbsG = draft.carbsG.value,
        sugarG = draft.sugarG.value,
        fatG = draft.fatG.value,
        satFatG = draft.satFatG.value,
        fiberG = draft.fiberG.value,
        saltG = draft.saltG.value,
        sodiumG = draft.sodiumG.value,
    )

    /** How many nutrient slots (energy in either unit counts once) carry a value. */
    fun recognisedFieldCount(draft: NutritionFactsDraft): Int {
        val energy = if (draft.energyKcal.value != null || draft.energyKj.value != null) 1 else 0
        val nutrients = listOfNotNull(
            draft.proteinG.value,
            draft.carbsG.value,
            draft.sugarG.value,
            draft.fatG.value,
            draft.satFatG.value,
            draft.fiberG.value,
            draft.saltG.value,
            draft.sodiumG.value,
        ).size
        return energy + nutrients
    }

    /** Runs the step-7 checks. Never fails a parse — every check only appends a warning. */
    fun validate(draft: NutritionFactsDraft): List<EngineWarning> =
        NutritionValidator.validate(toFacts(draft), hasEnergyKj = draft.energyKj.value != null)
}
