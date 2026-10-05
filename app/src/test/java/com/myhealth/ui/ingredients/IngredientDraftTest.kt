package com.myhealth.ui.ingredients

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFactsDraft
import com.myhealth.domain.model.ParsedValue
import com.myhealth.testutil.Fixtures
import com.myhealth.ui.camera.ScanDraft
import org.junit.Test

/**
 * Validation + round-trip tests for [IngredientDraft] (PLAN P4.3), plus the scan prefill the OCR
 * review and the Open Food Facts lookup share (P4.9/P4.10).
 */
class IngredientDraftTest {

    @Test
    fun negative_kcal_is_rejected() {
        val draft = validDraft().copy(kcal = -1.0)

        assertThat(validate(draft)).containsKey(IngredientField.KCAL)
    }

    @Test
    fun missing_name_is_rejected() {
        val draft = validDraft().copy(name = "  ")

        assertThat(validate(draft)).containsKey(IngredientField.NAME)
    }

    @Test
    fun per_piece_without_piece_grams_is_rejected() {
        val draft = validDraft().copy(basis = MeasureBasis.PER_PIECE, pieceGrams = null)

        assertThat(validate(draft)).containsKey(IngredientField.PIECE_GRAMS)
    }

    @Test
    fun missing_required_macro_is_rejected() {
        val draft = validDraft().copy(proteinG = null)

        assertThat(validate(draft)).containsKey(IngredientField.PROTEIN)
    }

    @Test
    fun negative_optional_field_is_rejected() {
        val draft = validDraft().copy(sugarG = -0.5)

        assertThat(validate(draft)).containsKey(IngredientField.SUGAR)
    }

    @Test
    fun a_valid_draft_has_no_errors_and_round_trips_through_an_ingredient() {
        val draft = validDraft()
        assertThat(validate(draft)).isEmpty()

        val clock = Fixtures.fixedClock("2026-09-12T12:00:00Z")
        val ingredient: Ingredient = draft.toIngredient(clock)

        assertThat(ingredient.name).isEqualTo(draft.name)
        assertThat(ingredient.kcal).isEqualTo(draft.kcal)
        assertThat(ingredient.proteinG).isEqualTo(draft.proteinG)
        assertThat(ingredient.carbsG).isEqualTo(draft.carbsG)
        assertThat(ingredient.fatG).isEqualTo(draft.fatG)
        assertThat(ingredient.saltG).isEqualTo(draft.saltG)
        assertThat(ingredient.sodiumG).isWithin(1e-9).of(draft.saltG!! / 2.5)

        val roundTripped = fromIngredient(ingredient)
        assertThat(roundTripped.name).isEqualTo(draft.name)
        assertThat(roundTripped.basis).isEqualTo(draft.basis)
        assertThat(roundTripped.kcal).isEqualTo(draft.kcal)
        assertThat(validate(roundTripped)).isEmpty()
    }

    @Test
    fun a_scan_prefills_the_form_without_erasing_what_is_already_there() {
        val existing = IngredientDraft(name = "My oats", brand = "Homemade", fiberG = 9.9)
        val scan = ScanDraft(
            facts = NutritionFactsDraft(
                basis = MeasureBasis.PER_100ML,
                energyKcal = ParsedValue(46.0, 1.0),
                proteinG = ParsedValue(1.1, 1.0),
                carbsG = ParsedValue(7.4, 1.0),
                fatG = ParsedValue(1.5, 1.0),
                saltG = ParsedValue(0.12, 1.0),
                servingGrams = 250.0,
                servingLabel = "250 ml",
            ),
            name = "Oat drink",
            brand = "Storebrand",
            barcode = "7350002402238",
            source = ScanDraft.SOURCE_OFF,
        )

        val prefilled = existing.withScan(scan)

        assertThat(prefilled.name).isEqualTo("Oat drink")
        assertThat(prefilled.brand).isEqualTo("Storebrand")
        assertThat(prefilled.barcode).isEqualTo("7350002402238")
        assertThat(prefilled.basis).isEqualTo(MeasureBasis.PER_100ML)
        assertThat(prefilled.kcal).isEqualTo(46.0)
        assertThat(prefilled.saltG).isEqualTo(0.12)
        assertThat(prefilled.servingGrams).isEqualTo(250.0)
        assertThat(prefilled.servingLabel).isEqualTo("250 ml")
        assertThat(prefilled.source).isEqualTo("OFF")
        // Fiber was not on the label, so the value already in the form survives.
        assertThat(prefilled.fiberG).isEqualTo(9.9)
        assertThat(validate(prefilled)).isEmpty()
    }

    @Test
    fun a_per_serving_scan_becomes_a_per_piece_ingredient_with_that_piece_weight() {
        val scan = ScanDraft(
            facts = NutritionFactsDraft(
                basis = MeasureBasis.PER_PIECE,
                energyKcal = ParsedValue(124.0, 1.0),
                proteinG = ParsedValue(2.3, 1.0),
                carbsG = ParsedValue(15.9, 1.0),
                fatG = ParsedValue(5.2, 1.0),
                servingGrams = 30.0,
                servingLabel = "1 bar (30 g)",
            ),
            name = "Fruit bar",
        )

        val prefilled = newIngredientDraft().withScan(scan)

        assertThat(prefilled.basis).isEqualTo(MeasureBasis.PER_PIECE)
        assertThat(prefilled.pieceGrams).isEqualTo(30.0)
        assertThat(prefilled.source).isEqualTo("OCR")
        assertThat(validate(prefilled)).isEmpty()
    }

    private fun validDraft(): IngredientDraft = IngredientDraft(
        name = "Oats",
        basis = MeasureBasis.PER_100G,
        kcal = 370.0,
        proteinG = 13.5,
        carbsG = 58.7,
        fatG = 7.0,
        saltG = 0.02,
    )
}
