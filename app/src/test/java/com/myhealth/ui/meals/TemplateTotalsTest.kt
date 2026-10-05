package com.myhealth.ui.meals

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.nutrition.MealLine
import com.myhealth.domain.engine.nutrition.MealMath
import com.myhealth.domain.engine.nutrition.MealQuantity
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.MealTemplateItem
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.QuantityUnit
import org.junit.Test

/**
 * The templates list's totals must be exactly `MealMath` over the template's items (PLAN P4.4
 * acceptance) — no rounding, no shortcuts, and items whose ingredient is gone contribute nothing.
 */
class TemplateTotalsTest {

    private val oats = ingredient(1L, "Rolled oats", MeasureBasis.PER_100G, kcal = 370.0, protein = 13.0)
    private val milk = ingredient(
        id = 2L,
        name = "Whole milk",
        basis = MeasureBasis.PER_100ML,
        kcal = 64.0,
        protein = 3.4,
    )
    private val egg = ingredient(
        id = 3L,
        name = "Egg",
        basis = MeasureBasis.PER_PIECE,
        kcal = 78.0,
        protein = 6.3,
        pieceGrams = 50.0,
    )
    private val byId = listOf(oats, milk, egg).associateBy { it.id }

    @Test
    fun totals_equal_meal_math_over_the_items() {
        val items = listOf(
            item(1, oats.id, 80.0, QuantityUnit.G, sortOrder = 0),
            item(2, milk.id, 200.0, QuantityUnit.ML, sortOrder = 1),
            item(3, egg.id, 2.0, QuantityUnit.PIECE, sortOrder = 2),
        )

        val totals = templateTotals(items, byId)

        val expected = MealMath.totals(
            listOf(
                MealLine(MealQuantity(80.0, QuantityUnit.G), oats),
                MealLine(MealQuantity(200.0, QuantityUnit.ML), milk),
                MealLine(MealQuantity(2.0, QuantityUnit.PIECE), egg),
            ),
        ).totals
        assertThat(totals).isEqualTo(expected)
        // 0.8*370 + 2*64 + 2*78 = 296 + 128 + 156
        assertThat(totals.kcal).isWithin(1e-9).of(580.0)
        assertThat(totals.proteinG).isWithin(1e-9).of(10.4 + 6.8 + 12.6)
    }

    @Test
    fun totals_ignore_an_item_whose_ingredient_is_missing() {
        val items = listOf(
            item(1, oats.id, 100.0, QuantityUnit.G, sortOrder = 0),
            item(2, 99L, 100.0, QuantityUnit.G, sortOrder = 1),
        )

        val row = templateRow(template(items), byId)

        assertThat(row.totals.kcal).isWithin(1e-9).of(370.0)
        assertThat(row.missingIngredients).isTrue()
    }

    @Test
    fun an_empty_template_totals_zero() {
        val row = templateRow(template(emptyList()), byId)

        assertThat(row.totals.kcal).isEqualTo(0.0)
        assertThat(row.missingIngredients).isFalse()
    }

    @Test
    fun sort_order_does_not_change_the_sum() {
        val ascending = listOf(
            item(1, oats.id, 80.0, QuantityUnit.G, sortOrder = 0),
            item(2, milk.id, 200.0, QuantityUnit.ML, sortOrder = 1),
        )
        val shuffled = ascending.reversed()

        assertThat(templateTotals(shuffled, byId)).isEqualTo(templateTotals(ascending, byId))
    }

    @Test
    fun log_now_defaults_to_the_template_slot_then_lunch() {
        assertThat(defaultSlotFor(template(emptyList(), slot = MealSlot.DINNER))).isEqualTo(MealSlot.DINNER)
        assertThat(defaultSlotFor(template(emptyList(), slot = null))).isEqualTo(MealSlot.LUNCH)
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private fun template(items: List<MealTemplateItem>, slot: MealSlot? = MealSlot.BREAKFAST) = MealTemplate(
        id = 1L,
        name = "Oatmeal",
        defaultSlot = slot,
        note = null,
        isFavorite = false,
        useCount = 0,
        lastUsedAtMillis = null,
        archived = false,
        items = items,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    private fun item(
        id: Long,
        ingredientId: Long,
        quantity: Double,
        unit: QuantityUnit,
        sortOrder: Int,
    ) = MealTemplateItem(
        id = id,
        templateId = 1L,
        ingredientId = ingredientId,
        quantity = quantity,
        unit = unit,
        sortOrder = sortOrder,
    )

    private fun ingredient(
        id: Long,
        name: String,
        basis: MeasureBasis,
        kcal: Double,
        protein: Double,
        pieceGrams: Double? = null,
        servingGrams: Double? = null,
    ) = Ingredient(
        id = id,
        name = name,
        brand = null,
        barcode = null,
        basis = basis,
        pieceGrams = pieceGrams,
        servingGrams = servingGrams,
        servingLabel = null,
        kcal = kcal,
        proteinG = protein,
        carbsG = 10.0,
        sugarG = 2.0,
        fatG = 5.0,
        satFatG = 1.0,
        fiberG = 3.0,
        saltG = 0.1,
        sodiumG = 0.04,
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
