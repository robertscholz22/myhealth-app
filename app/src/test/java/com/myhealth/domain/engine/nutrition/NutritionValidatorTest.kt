package com.myhealth.domain.engine.nutrition

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFacts
import org.junit.Test

/** The §3.6.1 step-7 checks (P4.1), shared by the label parser (P4.7) and the ingredient editor
 * (P4.3). Each test isolates one rule. */
class NutritionValidatorTest {

    @Test
    fun nutval01_sat_fat_greater_than_fat_warns() {
        val facts = facts(kcal = 100.0, fatG = 5.0, satFatG = 8.0)

        val warnings = NutritionValidator.validate(facts)

        assertThat(warnings.map { it.code }).contains(EngineWarningCode.IMPLAUSIBLE_VALUE)
    }

    @Test
    fun nutval02_sugar_greater_than_carbs_warns() {
        val facts = facts(kcal = 100.0, carbsG = 10.0, sugarG = 15.0)

        val warnings = NutritionValidator.validate(facts)

        assertThat(warnings.map { it.code }).contains(EngineWarningCode.IMPLAUSIBLE_VALUE)
    }

    @Test
    fun nutval03_per_100_value_over_100_warns() {
        val facts = facts(basis = MeasureBasis.PER_100G, kcal = 100.0, proteinG = 120.0)

        val warnings = NutritionValidator.validate(facts)

        assertThat(warnings.map { it.code }).contains(EngineWarningCode.IMPLAUSIBLE_VALUE)
    }

    @Test
    fun nutval04_atwater_mismatch_warns() {
        // 4*0 + 4*0 + 9*0 + 2*0 = 0 computed kcal, vs a stated 400 kcal -> way past max(30, 100).
        val facts = facts(kcal = 400.0)

        val warnings = NutritionValidator.validate(facts)

        assertThat(warnings.map { it.code }).contains(EngineWarningCode.ENERGY_MISMATCH)
    }

    @Test
    fun nutval05_missing_energy_and_few_fields_warns_low_confidence() {
        val facts = facts(kcal = null, proteinG = 5.0)

        val warnings = NutritionValidator.validate(facts)

        assertThat(warnings.map { it.code }).contains(EngineWarningCode.LOW_CONFIDENCE)
    }

    @Test
    fun a_plausible_fully_populated_label_has_no_warnings() {
        // 4*13.5 + 4*58.7 + 9*7.0 + 2*10.0 = 54 + 234.8 + 63 + 20 = 371.8, within 30 of 370.
        val facts = facts(
            kcal = 370.0,
            proteinG = 13.5,
            carbsG = 58.7,
            sugarG = 1.0,
            fatG = 7.0,
            satFatG = 1.2,
            fiberG = 10.0,
            saltG = 0.02,
        )

        assertThat(NutritionValidator.validate(facts)).isEmpty()
    }

    private fun facts(
        basis: MeasureBasis = MeasureBasis.PER_100G,
        kcal: Double?,
        proteinG: Double? = null,
        carbsG: Double? = null,
        sugarG: Double? = null,
        fatG: Double? = null,
        satFatG: Double? = null,
        fiberG: Double? = null,
        saltG: Double? = null,
    ): NutritionFacts = NutritionFacts(
        basis = basis,
        kcal = kcal,
        proteinG = proteinG,
        carbsG = carbsG,
        sugarG = sugarG,
        fatG = fatG,
        satFatG = satFatG,
        fiberG = fiberG,
        saltG = saltG,
        sodiumG = null,
    )
}
