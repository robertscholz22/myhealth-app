package com.myhealth.domain.engine.nutrition

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.QuantityUnit
import org.junit.Test

/**
 * Named tests from PLAN §3.7 (P4.1). Each ingredient's nutrient fields are expressed per its
 * [Ingredient.basis] unit; [MealMath] scales them to the logged quantity.
 */
class MealMathTest {

    @Test
    fun meal01_per100g_scaling() {
        val oats = ingredient(basis = MeasureBasis.PER_100G, kcal = 370.0, proteinG = 13.5, carbsG = 58.7, fatG = 7.0)

        val result = MealMath.nutrientsFor(MealQuantity(quantity = 50.0, unit = QuantityUnit.G), oats)

        assertThat(result.totals.kcal).isWithin(1e-9).of(185.0)
        assertThat(result.totals.proteinG).isWithin(1e-9).of(6.75)
        assertThat(result.totals.carbsG).isWithin(1e-9).of(29.35)
        assertThat(result.totals.fatG).isWithin(1e-9).of(3.5)
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun meal02_piece_based_ingredient() {
        val egg = ingredient(basis = MeasureBasis.PER_PIECE, pieceGrams = 50.0, kcal = 78.0, proteinG = 6.5)

        val result = MealMath.nutrientsFor(MealQuantity(quantity = 2.0, unit = QuantityUnit.PIECE), egg)

        assertThat(MealMath.gramsOf(MealQuantity(2.0, QuantityUnit.PIECE), egg)).isWithin(1e-9).of(100.0)
        assertThat(result.totals.kcal).isWithin(1e-9).of(156.0)
        assertThat(result.totals.proteinG).isWithin(1e-9).of(13.0)
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun meal03_serving_unit_falls_back_to_100g() {
        val bar = ingredient(basis = MeasureBasis.PER_100G, servingGrams = null, pieceGrams = null, kcal = 200.0)

        val grams = MealMath.gramsOf(MealQuantity(quantity = 1.0, unit = QuantityUnit.SERVING), bar)
        val result = MealMath.nutrientsFor(MealQuantity(quantity = 1.0, unit = QuantityUnit.SERVING), bar)

        assertThat(grams).isWithin(1e-9).of(100.0)
        assertThat(result.totals.kcal).isWithin(1e-9).of(200.0)
    }

    @Test
    fun meal04_ml_treated_as_grams_for_per_100ml() {
        val milk = ingredient(basis = MeasureBasis.PER_100ML, kcal = 64.0, proteinG = 3.4, fatG = 3.6)

        val result = MealMath.nutrientsFor(MealQuantity(quantity = 250.0, unit = QuantityUnit.ML), milk)

        assertThat(result.totals.kcal).isWithin(1e-9).of(160.0)
        assertThat(result.totals.proteinG).isWithin(1e-9).of(8.5)
        assertThat(result.totals.fatG).isWithin(1e-9).of(9.0)
    }

    @Test
    fun meal05_totals_sum_across_items() {
        val oats = ingredient(basis = MeasureBasis.PER_100G, kcal = 370.0, proteinG = 13.5)
        val milk = ingredient(basis = MeasureBasis.PER_100ML, kcal = 64.0, proteinG = 3.4)

        val result = MealMath.totals(
            listOf(
                MealLine(MealQuantity(50.0, QuantityUnit.G), oats),
                MealLine(MealQuantity(250.0, QuantityUnit.ML), milk),
            ),
        )

        // oats: 185 kcal / 6.75 g protein; milk: 160 kcal / 8.5 g protein
        assertThat(result.totals.kcal).isWithin(1e-9).of(345.0)
        assertThat(result.totals.proteinG).isWithin(1e-9).of(15.25)
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun meal06_missing_piece_grams_warns_and_contributes_zero() {
        val mysteryFruit = ingredient(basis = MeasureBasis.PER_PIECE, pieceGrams = null, kcal = 95.0)

        val grams = MealMath.gramsOf(MealQuantity(quantity = 1.0, unit = QuantityUnit.PIECE), mysteryFruit)
        val result = MealMath.nutrientsFor(MealQuantity(quantity = 1.0, unit = QuantityUnit.PIECE), mysteryFruit)

        assertThat(grams).isEqualTo(0.0)
        assertThat(result.totals.kcal).isEqualTo(0.0)
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single().code).isEqualTo(EngineWarningCode.MISSING_WEIGHT)
    }

    private fun ingredient(
        basis: MeasureBasis,
        kcal: Double,
        proteinG: Double? = null,
        carbsG: Double? = null,
        fatG: Double? = null,
        pieceGrams: Double? = if (basis == MeasureBasis.PER_PIECE) 100.0 else null,
        servingGrams: Double? = null,
    ): Ingredient = Ingredient(
        id = 1L,
        name = "Test ingredient",
        brand = null,
        barcode = null,
        basis = basis,
        pieceGrams = pieceGrams,
        servingGrams = servingGrams,
        servingLabel = null,
        kcal = kcal,
        proteinG = proteinG,
        carbsG = carbsG,
        sugarG = null,
        fatG = fatG,
        satFatG = null,
        fiberG = null,
        saltG = null,
        sodiumG = null,
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
