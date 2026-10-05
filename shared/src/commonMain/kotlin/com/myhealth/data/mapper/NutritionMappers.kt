package com.myhealth.data.mapper

import com.myhealth.data.db.entity.IngredientEntity
import com.myhealth.data.db.entity.MealLogEntity
import com.myhealth.data.db.entity.MealLogItemEntity
import com.myhealth.data.db.entity.MealTemplateEntity
import com.myhealth.data.db.entity.MealTemplateItemEntity
import com.myhealth.data.db.entity.NutritionTargetSnapshotEntity
import com.myhealth.data.db.entity.WaterLogEntity
import com.myhealth.data.db.relation.MealLogWithItems
import com.myhealth.data.db.relation.MealTemplateWithItems
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealLog
import com.myhealth.domain.model.MealLogItem
import com.myhealth.domain.model.MealLogSummary
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.MealTemplateItem
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.WaterLog
import com.myhealth.domain.util.EngineWarning

/**
 * `ingredient`, `meal_template(+item)`, `meal_log(+item)`, `nutrition_target_snapshot` and
 * `water_log` ⇄ the domain nutrition models (PLAN §2.2.5 / P1.6).
 */

// ---- ingredient ⇄ Ingredient ----------------------------------------------------------------------

fun IngredientEntity.toDomain(): Ingredient = Ingredient(
    id = id,
    name = name,
    brand = brand,
    barcode = barcode,
    basis = basis,
    pieceGrams = pieceGrams,
    servingGrams = servingGrams,
    servingLabel = servingLabel,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    sugarG = sugarG,
    fatG = fatG,
    satFatG = satFatG,
    fiberG = fiberG,
    saltG = saltG,
    sodiumG = sodiumG,
    isFavorite = isFavorite,
    source = source,
    offProductJson = offProductJson,
    lastUsedAtMillis = lastUsedAtMillis,
    useCount = useCount,
    archived = archived,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun Ingredient.toEntity(): IngredientEntity = IngredientEntity(
    id = id,
    name = name,
    brand = brand,
    barcode = barcode,
    basis = basis,
    pieceGrams = pieceGrams,
    servingGrams = servingGrams,
    servingLabel = servingLabel,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    sugarG = sugarG,
    fatG = fatG,
    satFatG = satFatG,
    fiberG = fiberG,
    saltG = saltG,
    sodiumG = sodiumG,
    isFavorite = isFavorite,
    source = source,
    offProductJson = offProductJson,
    lastUsedAtMillis = lastUsedAtMillis,
    useCount = useCount,
    archived = archived,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

// ---- meal_template_item ⇄ MealTemplateItem --------------------------------------------------------

fun MealTemplateItemEntity.toDomain(): MealTemplateItem = MealTemplateItem(
    id = id,
    templateId = templateId,
    ingredientId = ingredientId,
    quantity = quantity,
    unit = unit,
    sortOrder = sortOrder,
)

fun MealTemplateItem.toEntity(): MealTemplateItemEntity = MealTemplateItemEntity(
    id = id,
    templateId = templateId,
    ingredientId = ingredientId,
    quantity = quantity,
    unit = unit,
    sortOrder = sortOrder,
)

// ---- meal_template (+items) ⇄ MealTemplate ---------------------------------------------------------

fun MealTemplateEntity.toDomain(items: List<MealTemplateItem> = emptyList()): MealTemplate = MealTemplate(
    id = id,
    name = name,
    defaultSlot = defaultSlot,
    note = note,
    isFavorite = isFavorite,
    useCount = useCount,
    lastUsedAtMillis = lastUsedAtMillis,
    archived = archived,
    items = items,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun MealTemplate.toEntity(): MealTemplateEntity = MealTemplateEntity(
    id = id,
    name = name,
    defaultSlot = defaultSlot,
    note = note,
    isFavorite = isFavorite,
    useCount = useCount,
    lastUsedAtMillis = lastUsedAtMillis,
    archived = archived,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

/** Assembles the domain aggregate from the Room `@Transaction` relation (§2.2.5). */
fun MealTemplateWithItems.toDomain(): MealTemplate =
    template.toDomain(items = items.map { it.toDomain() })

// ---- meal_log_item ⇄ MealLogItem (denormalized nutrient snapshot) ----------------------------------

fun MealLogItemEntity.toDomain(): MealLogItem = MealLogItem(
    id = id,
    mealLogId = mealLogId,
    ingredientId = ingredientId,
    nameSnapshot = nameSnapshot,
    quantity = quantity,
    unit = unit,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    sugarG = sugarG,
    fatG = fatG,
    satFatG = satFatG,
    fiberG = fiberG,
    saltG = saltG,
)

fun MealLogItem.toEntity(): MealLogItemEntity = MealLogItemEntity(
    id = id,
    mealLogId = mealLogId,
    ingredientId = ingredientId,
    nameSnapshot = nameSnapshot,
    quantity = quantity,
    unit = unit,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    sugarG = sugarG,
    fatG = fatG,
    satFatG = satFatG,
    fiberG = fiberG,
    saltG = saltG,
)

// ---- meal_log (+items) ⇄ MealLog -------------------------------------------------------------------

fun MealLogEntity.toDomain(items: List<MealLogItem> = emptyList()): MealLog = MealLog(
    id = id,
    day = day,
    atMinuteOfDay = atMinuteOfDay,
    slot = slot,
    name = name,
    templateId = templateId,
    note = note,
    items = items,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun MealLog.toEntity(): MealLogEntity = MealLogEntity(
    id = id,
    day = day,
    atMinuteOfDay = atMinuteOfDay,
    slot = slot,
    name = name,
    templateId = templateId,
    note = note,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

/** Assembles the domain aggregate from the Room `@Transaction` relation (§2.2.5). */
fun MealLogWithItems.toDomain(): MealLog = log.toDomain(items = items.map { it.toDomain() })

/**
 * Light view for aggregates such as `CalendarDay` (§2.3): the item snapshots are already absolute
 * values for the logged quantity, so the meal total is their plain sum.
 */
fun MealLogWithItems.toSummary(): MealLogSummary = MealLogSummary(
    id = log.id,
    day = log.day,
    atMinuteOfDay = log.atMinuteOfDay,
    slot = log.slot,
    name = log.name,
    totals = items.fold(MacroTotals.ZERO) { acc, item ->
        acc + MacroTotals(
            kcal = item.kcal,
            proteinG = item.proteinG,
            carbsG = item.carbsG,
            fatG = item.fatG,
            fiberG = item.fiberG,
            sugarG = item.sugarG,
            satFatG = item.satFatG,
            saltG = item.saltG,
        )
    },
)

// ---- nutrition_target_snapshot ⇄ NutritionTarget (warningsCsv ⇄ List<EngineWarning>) ---------------

/**
 * The cache row only stores [EngineWarningCode] names (§2.2.5 comment on `warningsCsv`) — the
 * human-readable [EngineWarning.message] is not persisted, so it decodes back as an empty string.
 * This is a deliberate, documented lossy edge (the cache is for display; the engine recomputes the
 * full warning list on demand), not covered by the mapper round-trip test.
 */
private fun List<EngineWarning>.toWarningsCsv(): String = joinToString(",") { it.code.name }

private fun String.toWarnings(): List<EngineWarning> =
    if (isBlank()) {
        emptyList()
    } else {
        split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { name -> EngineWarningCode.entries.firstOrNull { it.name == name } }
            .map { EngineWarning(it, "") }
    }

fun NutritionTargetSnapshotEntity.toDomain(): NutritionTarget = NutritionTarget(
    day = day,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    fiberG = fiberG,
    sugarCapG = sugarCapG,
    satFatCapG = satFatCapG,
    saltG = saltG,
    waterMl = waterMl,
    bmrKcal = bmrKcal,
    tdeeKcal = tdeeKcal,
    dayType = dayType,
    explanation = explanation,
    warnings = warningsCsv.toWarnings(),
    inputsHash = inputsHash,
    computedAtMillis = computedAtMillis,
)

fun NutritionTarget.toEntity(): NutritionTargetSnapshotEntity = NutritionTargetSnapshotEntity(
    day = day,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    fiberG = fiberG,
    sugarCapG = sugarCapG,
    satFatCapG = satFatCapG,
    saltG = saltG,
    waterMl = waterMl,
    bmrKcal = bmrKcal,
    tdeeKcal = tdeeKcal,
    dayType = dayType,
    explanation = explanation,
    warningsCsv = warnings.toWarningsCsv(),
    inputsHash = inputsHash,
    computedAtMillis = computedAtMillis,
)

// ---- water_log ⇄ WaterLog ---------------------------------------------------------------------------

fun WaterLogEntity.toDomain(): WaterLog = WaterLog(id = id, day = day, atMinuteOfDay = atMinuteOfDay, ml = ml)

fun WaterLog.toEntity(): WaterLogEntity = WaterLogEntity(id = id, day = day, atMinuteOfDay = atMinuteOfDay, ml = ml)
