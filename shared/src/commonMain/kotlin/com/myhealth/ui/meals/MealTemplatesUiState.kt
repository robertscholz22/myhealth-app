package com.myhealth.ui.meals

import com.myhealth.domain.engine.nutrition.MealLine
import com.myhealth.domain.engine.nutrition.MealMath
import com.myhealth.domain.engine.nutrition.MealQuantity
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.MealTemplateItem
import com.myhealth.ui.common.UiMessage

/** The slot "Log now" pre-selects when a template has no `defaultSlot` (§4.2 Meal templates). */
val FALLBACK_SLOT: MealSlot = MealSlot.LUNCH

/**
 * One row of the templates list: the template plus the [MacroTotals] computed with [MealMath]
 * over its items and their ingredients (§3.7). [missingIngredients] is `true` when at least one
 * item's ingredient could not be resolved (archived/deleted), so the row can say its totals are
 * incomplete instead of silently under-reporting.
 */
data class TemplateRow(
    val template: MealTemplate,
    val totals: MacroTotals,
    val missingIngredients: Boolean,
) {
    /** P24: [totals] are the whole recipe; this is one of its `servings`. */
    val perServing: MacroTotals
        get() = totals * MealMath.servingFactor(eaten = 1.0, made = template.servings)
}

/** ViewModel state for [MealTemplatesScreen] (PLAN §4.2 Meal templates, P4.4). */
data class MealTemplatesUiState(
    val isLoading: Boolean = true,
    val rows: List<TemplateRow> = emptyList(),
    /** Non-null while the "Log now" dialog is open. */
    val logTemplate: MealTemplate? = null,
    val logDay: Long = 0L,
    val logSlot: MealSlot = FALLBACK_SLOT,
    /** P24: servings eaten in the "Log now" dialog; `null` while the field is blank. */
    val logServings: Double? = 1.0,
    val message: UiMessage? = null,
) {
    /** Live totals of the pending "Log now", or `null` until [logServings] is above zero. */
    val logPreview: MacroTotals?
        get() {
            val template = logTemplate ?: return null
            val row = rows.firstOrNull { it.template.id == template.id } ?: return null
            return servingsPreview(row.totals, made = template.servings, eaten = logServings)
        }
}

/**
 * P24: what [eaten] servings of a recipe whose items make [made] servings and add up to [whole]
 * contain — the same factor `MealLogWriter.logTemplate` scales the logged items by. `null` while
 * [eaten] is blank or not above zero.
 */
fun servingsPreview(whole: MacroTotals, made: Double, eaten: Double?): MacroTotals? =
    eaten?.takeIf { it > 0.0 }?.let { whole * MealMath.servingFactor(eaten = it, made = made) }

/**
 * `MealMath.totals` over a template's items (§3.7, P4.4): every item whose ingredient resolves
 * contributes its absolute nutrients; unresolvable items contribute nothing.
 */
fun templateTotals(items: List<MealTemplateItem>, ingredients: Map<Long, Ingredient>): MacroTotals {
    val lines = items.sortedBy { it.sortOrder }.mapNotNull { item ->
        ingredients[item.ingredientId]?.let { MealLine(MealQuantity(item.quantity, item.unit), it) }
    }
    return MealMath.totals(lines).totals
}

fun templateRow(template: MealTemplate, ingredients: Map<Long, Ingredient>): TemplateRow = TemplateRow(
    template = template,
    totals = templateTotals(template.items, ingredients),
    missingIngredients = template.items.any { it.ingredientId !in ingredients },
)

/** "Log now" defaults: the template's `defaultSlot`, else lunch (§4.2 / P4.4). */
fun defaultSlotFor(template: MealTemplate): MealSlot = template.defaultSlot ?: FALLBACK_SLOT
