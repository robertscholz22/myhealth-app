package com.myhealth.domain.model

import com.myhealth.domain.util.EngineWarning

/** Mirrors `ingredient` (PLAN §2.2.5). Nutrient fields are expressed per [basis] unit. */
data class Ingredient(
    val id: Long,
    val name: String,
    val brand: String?,
    val barcode: String?,
    val basis: MeasureBasis,
    val pieceGrams: Double?,
    val servingGrams: Double?,
    val servingLabel: String?,
    val kcal: Double,
    val proteinG: Double?,
    val carbsG: Double?,
    val sugarG: Double?,
    val fatG: Double?,
    val satFatG: Double?,
    val fiberG: Double?,
    val saltG: Double?,
    val sodiumG: Double?,
    val isFavorite: Boolean,
    /** `MANUAL` / `OCR` / `OFF`. */
    val source: String,
    val offProductJson: String?,
    val lastUsedAtMillis: Long?,
    val useCount: Int,
    val archived: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** Mirrors `meal_template_item` (§2.2.5). */
data class MealTemplateItem(
    val id: Long,
    val templateId: Long,
    val ingredientId: Long,
    val quantity: Double,
    val unit: QuantityUnit,
    val sortOrder: Int,
)

/** Mirrors `meal_template` plus its items (§2.2.5). */
data class MealTemplate(
    val id: Long,
    val name: String,
    val defaultSlot: MealSlot?,
    val note: String?,
    val isFavorite: Boolean,
    val useCount: Int,
    val lastUsedAtMillis: Long?,
    val archived: Boolean,
    val items: List<MealTemplateItem> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * Mirrors `meal_log_item` (§2.2.5) — denormalized nutrient snapshot for the logged [quantity],
 * so editing an ingredient later never rewrites history.
 */
data class MealLogItem(
    val id: Long,
    val mealLogId: Long,
    val ingredientId: Long?,
    val nameSnapshot: String,
    val quantity: Double,
    val unit: QuantityUnit,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val sugarG: Double,
    val fatG: Double,
    val satFatG: Double,
    val fiberG: Double,
    val saltG: Double,
)

/** Mirrors `meal_log` plus its items (§2.2.5). */
data class MealLog(
    val id: Long,
    val day: Long,
    val atMinuteOfDay: Int?,
    val slot: MealSlot,
    val name: String?,
    /** Provenance only — items are copied, not linked live. */
    val templateId: Long?,
    val note: String?,
    val items: List<MealLogItem> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** Light view of a [MealLog] for aggregate views such as [CalendarDay] (§2.3). */
data class MealLogSummary(
    val id: Long,
    val day: Long,
    val atMinuteOfDay: Int?,
    val slot: MealSlot,
    val name: String?,
    val totals: MacroTotals,
)

/**
 * Sum of macro/energy fields for a meal or a day (§2.3). `+` sums every field; [ZERO] is the
 * additive identity.
 */
data class MacroTotals(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    val sugarG: Double,
    val satFatG: Double,
    val saltG: Double,
) {
    operator fun plus(other: MacroTotals): MacroTotals = MacroTotals(
        kcal = kcal + other.kcal,
        proteinG = proteinG + other.proteinG,
        carbsG = carbsG + other.carbsG,
        fatG = fatG + other.fatG,
        fiberG = fiberG + other.fiberG,
        sugarG = sugarG + other.sugarG,
        satFatG = satFatG + other.satFatG,
        saltG = saltG + other.saltG,
    )

    companion object {
        val ZERO = MacroTotals(
            kcal = 0.0,
            proteinG = 0.0,
            carbsG = 0.0,
            fatG = 0.0,
            fiberG = 0.0,
            sugarG = 0.0,
            satFatG = 0.0,
            saltG = 0.0,
        )
    }
}

/** Mirrors `nutrition_target_snapshot` — cached `NutritionTargetEngine` output (§2.2.5, §3.1). */
data class NutritionTarget(
    val day: Long,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fiberG: Int,
    val sugarCapG: Int,
    val satFatCapG: Int,
    val saltG: Double,
    val waterMl: Int,
    val bmrKcal: Int,
    val tdeeKcal: Int,
    val dayType: DayType,
    val explanation: String,
    val warnings: List<EngineWarning>,
    val inputsHash: String,
    val computedAtMillis: Long,
)

/** Confirmed nutrition facts for a product, per [basis] unit (e.g. an OFF product or a saved ingredient). */
data class NutritionFacts(
    val basis: MeasureBasis,
    val kcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val sugarG: Double?,
    val fatG: Double?,
    val satFatG: Double?,
    val fiberG: Double?,
    val saltG: Double?,
    val sodiumG: Double?,
)

/**
 * One parsed field of a label (§2.3, §3.6): the value (if any), the parser's confidence, which
 * OCR line it came from, and whether it is a `"< x"` upper bound (e.g. `< 0.5 g`).
 */
data class ParsedValue(
    val value: Double? = null,
    val confidence: Double = 0.0,
    val sourceLineIndex: Int? = null,
    val isUpperBound: Boolean = false,
)

/**
 * Editable draft produced by the nutrition label parser (§3.6) before the user confirms it into
 * an [Ingredient]. Never written to storage on its own.
 */
data class NutritionFactsDraft(
    val basis: MeasureBasis,
    val energyKcal: ParsedValue = ParsedValue(),
    val energyKj: ParsedValue = ParsedValue(),
    val proteinG: ParsedValue = ParsedValue(),
    val carbsG: ParsedValue = ParsedValue(),
    val sugarG: ParsedValue = ParsedValue(),
    val fatG: ParsedValue = ParsedValue(),
    val satFatG: ParsedValue = ParsedValue(),
    val fiberG: ParsedValue = ParsedValue(),
    val saltG: ParsedValue = ParsedValue(),
    val sodiumG: ParsedValue = ParsedValue(),
    val servingGrams: Double? = null,
    val servingLabel: String? = null,
    val perServing: NutritionFacts? = null,
    val warnings: List<EngineWarning> = emptyList(),
)

/** Mirrors `water_log` (§2.2.5). */
data class WaterLog(
    val id: Long,
    val day: Long,
    val atMinuteOfDay: Int?,
    val ml: Int,
)
