package com.myhealth.domain.engine.nutrition

import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.util.EngineWarning

/**
 * A quantity+unit the user picked for one ingredient (PLAN §3.7) — the input side of the meal
 * math, before nutrients are resolved. Distinct from `MealItemInput` (`domain/repository`), which
 * additionally carries the `ingredientId` the repository resolves before calling here.
 */
data class MealQuantity(val quantity: Double, val unit: QuantityUnit)

/** One priced line: a [MealQuantity] against the [Ingredient] it was priced against. */
data class MealLine(val item: MealQuantity, val ingredient: Ingredient)

/**
 * [MacroTotals] plus any warnings raised while pricing (§1.5: engines never throw, they clamp and
 * warn) — currently just the missing-piece-weight case of §3.7.
 */
data class MealMathResult(val totals: MacroTotals, val warnings: List<EngineWarning> = emptyList())

/**
 * Pure meal/ingredient math (PLAN §3.7, P4.1): resolves a logged quantity into grams, then into
 * absolute nutrients for the ingredient's `basis`. Never throws — a `PIECE` quantity against an
 * ingredient with no `pieceGrams` resolves to 0 g and raises a warning instead of failing.
 */
object MealMath {

    /**
     * Grams (millilitres for `ML`, treated as grams downstream — §3.7) that [item] resolves to
     * against [ingredient]:
     * - `G`/`ML` → the quantity itself.
     * - `PIECE` → `quantity * ingredient.pieceGrams`, or `0.0` when `pieceGrams` is missing.
     * - `SERVING` → `quantity * (ingredient.servingGrams ?: ingredient.pieceGrams ?: 100.0)`.
     */
    fun gramsOf(item: MealQuantity, ingredient: Ingredient): Double = when (item.unit) {
        QuantityUnit.G, QuantityUnit.ML -> item.quantity
        QuantityUnit.PIECE -> item.quantity * (ingredient.pieceGrams ?: 0.0)
        QuantityUnit.SERVING -> item.quantity * (ingredient.servingGrams ?: ingredient.pieceGrams ?: 100.0)
    }

    /**
     * Multiplier from grams to "per [Ingredient.basis] unit" scale (§3.7): grams ÷ 100 for
     * `PER_100G`/`PER_100ML`, grams ÷ `pieceGrams` (default 100.0) for `PER_PIECE`.
     */
    fun factor(ingredient: Ingredient, grams: Double): Double = when (ingredient.basis) {
        MeasureBasis.PER_100G, MeasureBasis.PER_100ML -> grams / 100.0
        MeasureBasis.PER_PIECE -> grams / (ingredient.pieceGrams ?: 100.0)
    }

    /**
     * Absolute (unrounded — §3.7 rounding is a display concern) nutrients for one [MealLine]'s
     * item against its ingredient, plus a [EngineWarningCode.MISSING_WEIGHT] warning when a
     * `PIECE` quantity could not be resolved because the ingredient has no `pieceGrams`.
     */
    fun nutrientsFor(item: MealQuantity, ingredient: Ingredient): MealMathResult {
        val grams = gramsOf(item, ingredient)
        val warnings = if (item.unit == QuantityUnit.PIECE && ingredient.pieceGrams == null) {
            listOf(
                EngineWarning(
                    EngineWarningCode.MISSING_WEIGHT,
                    "${ingredient.name} has no piece weight; this item was counted as 0 g.",
                ),
            )
        } else {
            emptyList()
        }
        val f = factor(ingredient, grams)
        val totals = MacroTotals(
            kcal = ingredient.kcal * f,
            proteinG = (ingredient.proteinG ?: 0.0) * f,
            carbsG = (ingredient.carbsG ?: 0.0) * f,
            fatG = (ingredient.fatG ?: 0.0) * f,
            fiberG = (ingredient.fiberG ?: 0.0) * f,
            sugarG = (ingredient.sugarG ?: 0.0) * f,
            satFatG = (ingredient.satFatG ?: 0.0) * f,
            saltG = (ingredient.saltG ?: 0.0) * f,
        )
        return MealMathResult(totals, warnings)
    }

    /** Sums [nutrientsFor] over every line — a meal's totals from its items, or a day's totals
     * from its meals' totals-as-lines (§3.7: "Σ over items"/"Σ over meals" is the same fold). */
    fun totals(lines: List<MealLine>): MealMathResult {
        var acc = MacroTotals.ZERO
        val warnings = mutableListOf<EngineWarning>()
        for (line in lines) {
            val result = nutrientsFor(line.item, line.ingredient)
            acc = acc + result.totals
            warnings += result.warnings
        }
        return MealMathResult(acc, warnings)
    }
}
