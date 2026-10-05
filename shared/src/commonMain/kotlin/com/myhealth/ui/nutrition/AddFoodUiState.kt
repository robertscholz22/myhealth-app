package com.myhealth.ui.nutrition

import androidx.compose.runtime.Composable
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import com.myhealth.domain.engine.nutrition.MealMath
import com.myhealth.domain.engine.nutrition.MealQuantity
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.fmtDecimal

/** The Add-food tabs of §4.2 in display order; `SCAN` navigates out to `ScanRoute` (P4.8). */
enum class AddFoodTab { RECENTS, FAVORITES, SEARCH, TEMPLATES, SCAN }

@Composable
fun AddFoodTab.label(): String = when (this) {
    AddFoodTab.RECENTS -> stringResource(Res.string.addfood_tab_recents)
    AddFoodTab.FAVORITES -> stringResource(Res.string.addfood_tab_favorites)
    AddFoodTab.SEARCH -> stringResource(Res.string.addfood_tab_search)
    AddFoodTab.TEMPLATES -> stringResource(Res.string.addfood_tab_templates)
    AddFoodTab.SCAN -> stringResource(Res.string.addfood_tab_scan)
}

/** Display label for a logged quantity's unit (§2.1 `QuantityUnit`). */
fun QuantityUnit.label(): String = when (this) {
    QuantityUnit.G -> "g"
    QuantityUnit.ML -> "ml"
    QuantityUnit.PIECE -> "piece"
    QuantityUnit.SERVING -> "serving"
}

/** A one-tap quantity preset in [QuantityEditor] (§4.2 Add food: "quick-add chips"). */
data class QuickChip(val label: String, val quantity: Double, val unit: QuantityUnit)

/** ViewModel state for [AddFoodScreen] (PLAN §4.2 Add food, P4.6). */
data class AddFoodUiState(
    val day: Long,
    val slot: MealSlot,
    val tab: AddFoodTab = AddFoodTab.RECENTS,
    val query: String = "",
    val ingredients: List<Ingredient> = emptyList(),
    val templates: List<MealTemplate> = emptyList(),
    /** Non-null while the [QuantityEditor] for this ingredient is open. */
    val selected: Ingredient? = null,
    val quantity: Double? = null,
    val unit: QuantityUnit = QuantityUnit.G,
    val isSaving: Boolean = false,
    val message: UiMessage? = null,
    /** One-shot: the screen pops back once this flips to `true`. */
    val added: Boolean = false,
) {
    val validUnits: List<QuantityUnit>
        get() = selected?.let { validUnitsFor(it.basis, it) } ?: listOf(QuantityUnit.G)

    val quickChips: List<QuickChip>
        get() = selected?.let { quickChipsFor(it) } ?: emptyList()

    /** Live macro preview of the pending item (§4.2), or `null` until a quantity is typed. */
    val preview: MacroTotals?
        get() {
            val ingredient = selected ?: return null
            val amount = quantity ?: return null
            return MealMath.nutrientsFor(MealQuantity(amount, unit), ingredient).totals
        }

    val canAdd: Boolean get() = selected != null && (quantity ?: 0.0) > 0.0 && !isSaving
}

/**
 * The units that make sense for an ingredient priced on [basis] (§3.7 / §4.2 Add food).
 *
 * - The basis' own mass unit is always offered: `g` for `PER_100G`, `ml` for `PER_100ML`, and
 *   `piece` for `PER_PIECE`.
 * - `PIECE` needs a `pieceGrams` to resolve to a mass at all (`MealMath` counts a piece without
 *   one as 0 g), so it is only offered when that weight is known.
 * - A `PER_PIECE` ingredient can be weighed in grams only when `pieceGrams` is known — that is
 *   the divisor `MealMath.factor` uses.
 * - `SERVING` is offered only with an explicit `servingGrams`; `MealMath`'s 100 g fallback is a
 *   safety net for old data, not something the UI should invite.
 */
fun validUnitsFor(basis: MeasureBasis, ingredient: Ingredient): List<QuantityUnit> {
    val units = mutableListOf<QuantityUnit>()
    when (basis) {
        MeasureBasis.PER_100G -> units += QuantityUnit.G
        MeasureBasis.PER_100ML -> units += QuantityUnit.ML
        MeasureBasis.PER_PIECE -> {
            units += QuantityUnit.PIECE
            if (ingredient.pieceGrams != null) units += QuantityUnit.G
        }
    }
    if (basis != MeasureBasis.PER_PIECE && ingredient.pieceGrams != null) units += QuantityUnit.PIECE
    if (ingredient.servingGrams != null) units += QuantityUnit.SERVING
    return units
}

/**
 * Quick-add chips for [ingredient] (§4.2 Add food): 100 g / 100 ml of the basis' mass unit, one
 * serving when `servingGrams` is known (labelled with `servingLabel` when there is one), and one
 * piece when `pieceGrams` is known.
 */
fun quickChipsFor(ingredient: Ingredient): List<QuickChip> {
    val units = validUnitsFor(ingredient.basis, ingredient)
    val chips = mutableListOf<QuickChip>()
    when {
        QuantityUnit.ML in units -> chips += QuickChip("100 ml", 100.0, QuantityUnit.ML)
        QuantityUnit.G in units -> chips += QuickChip("100 g", 100.0, QuantityUnit.G)
    }
    ingredient.servingGrams?.let { grams ->
        val label = ingredient.servingLabel?.takeIf { it.isNotBlank() }
            ?: "1 serving (${formatGrams(grams)} g)"
        chips += QuickChip(label, 1.0, QuantityUnit.SERVING)
    }
    if (ingredient.pieceGrams != null) chips += QuickChip("1 piece", 1.0, QuantityUnit.PIECE)
    return chips
}

/** The unit an ingredient is most likely logged in — the first valid one for its basis. */
fun defaultUnitFor(ingredient: Ingredient): QuantityUnit =
    validUnitsFor(ingredient.basis, ingredient).first()

private fun formatGrams(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else fmtDecimal(value, 1)
