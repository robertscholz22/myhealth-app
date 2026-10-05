package com.myhealth.ui.meals

import com.myhealth.domain.engine.nutrition.MealLine
import com.myhealth.domain.engine.nutrition.MealMath
import com.myhealth.domain.engine.nutrition.MealQuantity
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.MealTemplateItem
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.ui.nutrition.defaultUnitFor
import com.myhealth.data.time.PlatformClock

/** Field identity for [validateTemplate] errors (mirrors `IngredientField`, P4.3). */
enum class TemplateField { NAME, ITEMS }

/**
 * One editable item row of the template editor. [ingredient] is the resolved ingredient (it drives
 * the valid units and the live totals); it is `null` only when the referenced ingredient has been
 * deleted or archived out from under the template.
 */
data class TemplateItemDraft(
    val ingredientId: Long,
    val name: String,
    val quantity: Double?,
    val unit: QuantityUnit,
    val ingredient: Ingredient?,
)

/** The meal-template editor's form state (PLAN §4.2 Meal template edit, P4.4). */
data class MealTemplateDraft(
    val id: Long = 0L,
    val name: String = "",
    val defaultSlot: MealSlot? = null,
    val note: String = "",
    val isFavorite: Boolean = false,
    val items: List<TemplateItemDraft> = emptyList(),
    val useCount: Int = 0,
    val lastUsedAtMillis: Long? = null,
    val createdAtMillis: Long = 0L,
)

/** Blocking errors; everything else (a missing ingredient, say) is surfaced as a warning row. */
fun validateTemplate(draft: MealTemplateDraft): Map<TemplateField, String> {
    val errors = mutableMapOf<TemplateField, String>()
    if (draft.name.isBlank()) errors[TemplateField.NAME] = "Name is required."
    if (draft.items.isEmpty()) {
        errors[TemplateField.ITEMS] = "Add at least one ingredient."
    } else if (draft.items.any { (it.quantity ?: 0.0) <= 0.0 }) {
        errors[TemplateField.ITEMS] = "Every ingredient needs a quantity above zero."
    }
    return errors
}

/** Live totals of the editor (§3.7 via [MealMath]) — unresolved rows contribute nothing. */
fun MealTemplateDraft.totals(): MacroTotals {
    val lines = items.mapNotNull { item ->
        val ingredient = item.ingredient ?: return@mapNotNull null
        MealLine(MealQuantity(item.quantity ?: 0.0, item.unit), ingredient)
    }
    return MealMath.totals(lines).totals
}

/** The domain template the editor saves; `sortOrder` is the row order (P4.4). */
fun MealTemplateDraft.toMealTemplate(clock: PlatformClock): MealTemplate {
    val now = clock.millis()
    return MealTemplate(
        id = id,
        name = name.trim(),
        defaultSlot = defaultSlot,
        note = note.trim().takeIf { it.isNotEmpty() },
        isFavorite = isFavorite,
        useCount = useCount,
        lastUsedAtMillis = lastUsedAtMillis,
        archived = false,
        items = items.mapIndexed { index, item ->
            MealTemplateItem(
                id = 0L,
                templateId = id,
                ingredientId = item.ingredientId,
                quantity = item.quantity ?: 0.0,
                unit = item.unit,
                sortOrder = index,
            )
        },
        createdAtMillis = if (id == 0L) now else createdAtMillis,
        updatedAtMillis = now,
    )
}

/** Loads an existing template into a draft, resolving each item against [ingredients]. */
fun templateDraftOf(template: MealTemplate, ingredients: Map<Long, Ingredient>): MealTemplateDraft =
    MealTemplateDraft(
        id = template.id,
        name = template.name,
        defaultSlot = template.defaultSlot,
        note = template.note.orEmpty(),
        isFavorite = template.isFavorite,
        items = template.items.sortedBy { it.sortOrder }.map { item ->
            val ingredient = ingredients[item.ingredientId]
            TemplateItemDraft(
                ingredientId = item.ingredientId,
                name = ingredient?.name ?: "Ingredient #${item.ingredientId}",
                quantity = item.quantity,
                unit = item.unit,
                ingredient = ingredient,
            )
        },
        useCount = template.useCount,
        lastUsedAtMillis = template.lastUsedAtMillis,
        createdAtMillis = template.createdAtMillis,
    )

/** A new row for [ingredient], pre-filled with its most natural unit (§4.2 Add food rules). */
fun itemDraftFor(ingredient: Ingredient): TemplateItemDraft = TemplateItemDraft(
    ingredientId = ingredient.id,
    name = ingredient.name,
    quantity = defaultQuantityFor(ingredient),
    unit = defaultUnitFor(ingredient),
    ingredient = ingredient,
)

private fun defaultQuantityFor(ingredient: Ingredient): Double =
    when (defaultUnitFor(ingredient)) {
        QuantityUnit.G, QuantityUnit.ML -> 100.0
        QuantityUnit.PIECE, QuantityUnit.SERVING -> 1.0
    }
