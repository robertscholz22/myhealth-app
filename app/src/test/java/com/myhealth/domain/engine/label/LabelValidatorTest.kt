package com.myhealth.domain.engine.label

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFactsDraft
import com.myhealth.domain.model.ParsedValue
import org.junit.Test

/** Step 7 of PLAN §3.6.1 as it applies to a parsed draft; the rules themselves are shared with
 * `NutritionValidator` (P4.1). */
class LabelValidatorTest {

    @Test
    fun lval01_saturated_fat_above_total_fat_warns() {
        val draft = draft(kcal = 370.0, fat = 7.0, satFat = 9.0)

        assertThat(LabelValidator.validate(draft).map { it.code })
            .contains(EngineWarningCode.IMPLAUSIBLE_VALUE)
    }

    @Test
    fun lval02_sugar_above_carbohydrate_warns() {
        val draft = draft(kcal = 370.0, carbs = 58.7, sugar = 61.1)

        assertThat(LabelValidator.validate(draft).map { it.code })
            .contains(EngineWarningCode.IMPLAUSIBLE_VALUE)
    }

    @Test
    fun lval03_atwater_mismatch_warns() {
        val draft = draft(kcal = 370.0, protein = 13.5, carbs = 58.7, fat = 70.0, fiber = 10.0)

        assertThat(LabelValidator.validate(draft).map { it.code })
            .contains(EngineWarningCode.ENERGY_MISMATCH)
    }

    @Test
    fun lval04_plausible_draft_produces_no_warnings() {
        val draft = draft(
            kcal = 370.0,
            protein = 13.5,
            carbs = 58.7,
            sugar = 1.1,
            fat = 7.0,
            satFat = 1.3,
            fiber = 10.0,
            salt = 0.02,
        )

        assertThat(LabelValidator.validate(draft)).isEmpty()
    }

    @Test
    fun lval05_kilojoule_only_draft_keeps_the_missing_energy_warning_away() {
        val kjOnly = NutritionFactsDraft(
            basis = MeasureBasis.PER_100G,
            energyKj = ParsedValue(1560.0, 1.0, 2),
            proteinG = ParsedValue(12.0, 1.0, 5),
            carbsG = ParsedValue(30.0, 1.0, 4),
            fatG = ParsedValue(20.0, 1.0, 3),
        )

        val warnings = LabelValidator.validate(kjOnly).map { it.code }

        assertThat(warnings).doesNotContain(EngineWarningCode.LOW_CONFIDENCE)
        assertThat(LabelValidator.recognisedFieldCount(kjOnly)).isEqualTo(4)
    }

    @Test
    fun lval06_recognised_field_count_counts_energy_once_and_zero_for_an_empty_draft() {
        val empty = NutritionFactsDraft(basis = MeasureBasis.PER_100G)
        val both = draft(kcal = 370.0).copy(energyKj = ParsedValue(1548.0, 1.0, 2))

        assertThat(LabelValidator.recognisedFieldCount(empty)).isEqualTo(0)
        assertThat(LabelValidator.recognisedFieldCount(both)).isEqualTo(1)
        assertThat(LabelValidator.validate(empty).map { it.code })
            .contains(EngineWarningCode.LOW_CONFIDENCE)
    }

    @Test
    fun lval07_facts_view_carries_every_parsed_value() {
        val facts = LabelValidator.toFacts(draft(kcal = 65.0, protein = 3.4, fat = 3.5, salt = 0.13))

        assertThat(facts.kcal).isEqualTo(65.0)
        assertThat(facts.proteinG).isEqualTo(3.4)
        assertThat(facts.fatG).isEqualTo(3.5)
        assertThat(facts.saltG).isEqualTo(0.13)
        assertThat(facts.basis).isEqualTo(MeasureBasis.PER_100G)
    }

    private fun draft(
        kcal: Double? = null,
        protein: Double? = null,
        carbs: Double? = null,
        sugar: Double? = null,
        fat: Double? = null,
        satFat: Double? = null,
        fiber: Double? = null,
        salt: Double? = null,
    ) = NutritionFactsDraft(
        basis = MeasureBasis.PER_100G,
        energyKcal = ParsedValue(kcal, if (kcal == null) 0.0 else 1.0, 0),
        proteinG = ParsedValue(protein, if (protein == null) 0.0 else 1.0, 0),
        carbsG = ParsedValue(carbs, if (carbs == null) 0.0 else 1.0, 0),
        sugarG = ParsedValue(sugar, if (sugar == null) 0.0 else 1.0, 0),
        fatG = ParsedValue(fat, if (fat == null) 0.0 else 1.0, 0),
        satFatG = ParsedValue(satFat, if (satFat == null) 0.0 else 1.0, 0),
        fiberG = ParsedValue(fiber, if (fiber == null) 0.0 else 1.0, 0),
        saltG = ParsedValue(salt, if (salt == null) 0.0 else 1.0, 0),
    )
}
