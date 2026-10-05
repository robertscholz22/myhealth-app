package com.myhealth.ui.nutrition

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.QuantityUnit
import org.junit.Test

/**
 * The pure parts of the Add-food state (PLAN P4.6): which units an ingredient may be logged in,
 * and which quick-add chips it offers.
 */
class AddFoodUiStateTest {

    @Test
    fun valid_units_for_a_plain_per_100g_ingredient_are_grams_only() {
        val oats = ingredient(basis = MeasureBasis.PER_100G)

        assertThat(validUnitsFor(oats.basis, oats)).containsExactly(QuantityUnit.G).inOrder()
    }

    @Test
    fun valid_units_add_piece_and_serving_when_their_weights_are_known() {
        val bread = ingredient(basis = MeasureBasis.PER_100G, pieceGrams = 30.0, servingGrams = 60.0)

        assertThat(validUnitsFor(bread.basis, bread))
            .containsExactly(QuantityUnit.G, QuantityUnit.PIECE, QuantityUnit.SERVING).inOrder()
    }

    @Test
    fun valid_units_for_a_per_piece_ingredient_are_pieces_plus_grams_when_a_piece_weight_exists() {
        val egg = ingredient(basis = MeasureBasis.PER_PIECE, pieceGrams = 50.0)
        val unweighedEgg = ingredient(basis = MeasureBasis.PER_PIECE)

        assertThat(validUnitsFor(egg.basis, egg))
            .containsExactly(QuantityUnit.PIECE, QuantityUnit.G).inOrder()
        assertThat(validUnitsFor(unweighedEgg.basis, unweighedEgg)).containsExactly(QuantityUnit.PIECE)
    }

    @Test
    fun a_per_100ml_ingredient_is_measured_in_millilitres() {
        val milk = ingredient(basis = MeasureBasis.PER_100ML)

        assertThat(validUnitsFor(milk.basis, milk)).containsExactly(QuantityUnit.ML)
        assertThat(quickChipsFor(milk).map { it.label }).containsExactly("100 ml")
        assertThat(quickChipsFor(milk).single().unit).isEqualTo(QuantityUnit.ML)
    }

    @Test
    fun quick_chips_for_a_plain_per_100g_ingredient_offer_only_100_g() {
        val oats = ingredient(basis = MeasureBasis.PER_100G)

        val chips = quickChipsFor(oats)

        assertThat(chips.map { it.label }).containsExactly("100 g")
        assertThat(chips.single().quantity).isEqualTo(100.0)
        assertThat(chips.single().unit).isEqualTo(QuantityUnit.G)
    }

    @Test
    fun quick_chips_add_the_serving_and_the_piece_when_their_weights_are_known() {
        val bread = ingredient(
            basis = MeasureBasis.PER_100G,
            pieceGrams = 30.0,
            servingGrams = 60.0,
            servingLabel = "2 Scheiben (60 g)",
        )

        val chips = quickChipsFor(bread)

        assertThat(chips.map { it.label })
            .containsExactly("100 g", "2 Scheiben (60 g)", "1 piece").inOrder()
        assertThat(chips[1].unit).isEqualTo(QuantityUnit.SERVING)
        assertThat(chips[1].quantity).isEqualTo(1.0)
        assertThat(chips[2].unit).isEqualTo(QuantityUnit.PIECE)
    }

    @Test
    fun a_serving_without_a_label_is_named_after_its_weight() {
        val oats = ingredient(basis = MeasureBasis.PER_100G, servingGrams = 40.0)

        assertThat(quickChipsFor(oats).map { it.label }).containsExactly("100 g", "1 serving (40 g)").inOrder()
    }

    @Test
    fun the_default_unit_is_the_first_valid_one() {
        assertThat(defaultUnitFor(ingredient(basis = MeasureBasis.PER_100G))).isEqualTo(QuantityUnit.G)
        assertThat(defaultUnitFor(ingredient(basis = MeasureBasis.PER_100ML))).isEqualTo(QuantityUnit.ML)
        assertThat(defaultUnitFor(ingredient(basis = MeasureBasis.PER_PIECE, pieceGrams = 50.0)))
            .isEqualTo(QuantityUnit.PIECE)
    }

    @Test
    fun the_preview_prices_the_pending_item_with_meal_math() {
        val oats = ingredient(basis = MeasureBasis.PER_100G)
        val state = AddFoodUiState(day = 20_000L, slot = MealSlot.BREAKFAST)
            .copy(selected = oats, quantity = 250.0, unit = QuantityUnit.G)

        assertThat(state.preview!!.kcal).isWithin(1e-9).of(925.0)
        assertThat(state.canAdd).isTrue()
        assertThat(state.copy(quantity = null).preview).isNull()
        assertThat(state.copy(quantity = 0.0).canAdd).isFalse()
    }

    private fun ingredient(
        basis: MeasureBasis,
        pieceGrams: Double? = null,
        servingGrams: Double? = null,
        servingLabel: String? = null,
    ) = Ingredient(
        id = 1L,
        name = "Test ingredient",
        brand = null,
        barcode = null,
        basis = basis,
        pieceGrams = pieceGrams,
        servingGrams = servingGrams,
        servingLabel = servingLabel,
        kcal = 370.0,
        proteinG = 13.0,
        carbsG = 60.0,
        sugarG = 1.0,
        fatG = 7.0,
        satFatG = 1.0,
        fiberG = 10.0,
        saltG = 0.02,
        sodiumG = 0.008,
        isFavorite = false,
        source = "MANUAL",
        offProductJson = null,
        lastUsedAtMillis = null,
        useCount = 0,
        archived = false,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )
}
