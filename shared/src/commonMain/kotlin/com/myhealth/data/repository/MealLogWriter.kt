package com.myhealth.data.repository

import com.myhealth.data.db.dao.IngredientDao
import com.myhealth.data.db.dao.MealDao
import com.myhealth.data.db.entity.IngredientEntity
import com.myhealth.data.db.entity.MealLogEntity
import com.myhealth.data.db.entity.MealLogItemEntity
import com.myhealth.data.mapper.toDomain
import com.myhealth.domain.engine.nutrition.MealMath
import com.myhealth.domain.engine.nutrition.MealQuantity
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.repository.MealItemInput
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import com.myhealth.data.time.PlatformClock

/**
 * The write half of [RoomMealRepository] (PLAN §2.2.5, P4.5) — everything that has to obey the
 * **snapshot-on-log** rule lives here, split out of the repository to keep both files well under
 * the R10 size limit.
 *
 * The rule: a `meal_log_item` stores the *absolute* nutrients of the logged quantity, computed
 * once with [MealMath] (§3.7) at log time, plus the ingredient's name. Editing or deleting the
 * ingredient afterwards therefore never rewrites history — the only thing that re-snapshots is an
 * explicit quantity edit ([updateItemQuantity]), and [copyDay] copies the stored snapshots
 * verbatim so a copied day reads exactly like the day it came from.
 */
class MealLogWriter(
    private val mealDao: MealDao,
    private val ingredientDao: IngredientDao,
    private val clock: PlatformClock,
) {

    /** Inserts a `meal_log` plus one snapshotted item per [items] entry; returns the log id. */
    suspend fun logMeal(
        day: Long,
        slot: MealSlot,
        items: List<MealItemInput>,
        name: String?,
        atMinuteOfDay: Int?,
        templateId: Long?,
    ): Outcome<Long> {
        if (items.isEmpty()) {
            return Outcome.Err(AppError.Validation("items", "A meal needs at least one ingredient."))
        }
        val ids = items.map { it.ingredientId }.distinct()
        val ingredients = ingredientDao.getByIds(ids).associateBy { it.id }
        val unknown = ids.firstOrNull { it !in ingredients }
        if (unknown != null) {
            return Outcome.Err(AppError.Validation("ingredientId", "That ingredient no longer exists."))
        }
        return runCatchingApp {
            val now = clock.millis()
            val logId = mealDao.upsert(
                MealLogEntity(
                    day = day,
                    atMinuteOfDay = atMinuteOfDay,
                    slot = slot,
                    name = name,
                    templateId = templateId,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                ),
            )
            mealDao.upsertItems(
                items.map { input -> snapshot(logId, input, ingredients.getValue(input.ingredientId)) },
            )
            // "Recents"/`useCount` are driven by logging, not by browsing (§2.2.5, P4.2).
            ids.forEach { ingredientDao.markUsed(it, now) }
            logId
        }
    }

    /**
     * Copies a template's items into a new `meal_log`, snapshotting each one at the ingredient's
     * *current* values, and bumps the template's `useCount`/`lastUsedAtMillis`.
     */
    suspend fun logTemplate(templateId: Long, day: Long, slot: MealSlot): Outcome<Long> {
        val template = mealDao.getTemplateWithItems(templateId)
            ?: return Outcome.Err(AppError.Validation("templateId", "That template no longer exists."))
        if (template.items.isEmpty()) {
            return Outcome.Err(AppError.Validation("items", "This template has no ingredients yet."))
        }
        val inputs = template.items.sortedBy { it.sortOrder }
            .map { MealItemInput(it.ingredientId, it.quantity, it.unit) }
        val result = logMeal(
            day = day,
            slot = slot,
            items = inputs,
            name = template.template.name,
            atMinuteOfDay = null,
            templateId = templateId,
        )
        if (result is Outcome.Ok) {
            val now = clock.millis()
            runCatchingApp {
                mealDao.upsertTemplate(
                    template.template.copy(
                        useCount = template.template.useCount + 1,
                        lastUsedAtMillis = now,
                        updatedAtMillis = now,
                    ),
                )
            }
        }
        return result
    }

    /**
     * Re-snapshots one item at a new quantity. When the ingredient still exists the snapshot is
     * recomputed from it with [MealMath]; when it has been deleted (`ingredientId` was
     * `SET_NULL`) the stored snapshot is scaled by the quantity ratio instead, which is the best
     * available answer without the ingredient's per-unit values.
     */
    suspend fun updateItemQuantity(itemId: Long, quantity: Double, unit: QuantityUnit): Outcome<Unit> {
        if (quantity < 0.0) {
            return Outcome.Err(AppError.Validation("quantity", "Quantity cannot be negative."))
        }
        return runCatchingApp {
            val item = mealDao.getItemById(itemId) ?: return@runCatchingApp
            val ingredient = item.ingredientId?.let { ingredientDao.getById(it) }
            val updated = if (ingredient != null) {
                snapshot(item.mealLogId, MealItemInput(ingredient.id, quantity, unit), ingredient)
                    .copy(id = item.id)
            } else {
                item.scaledTo(quantity, unit)
            }
            mealDao.upsertItem(updated)
            touch(item.mealLogId)
        }
    }

    /** Deletes one item; an emptied `meal_log` is removed with it rather than left as a ghost. */
    suspend fun deleteItem(itemId: Long): Outcome<Unit> = runCatchingApp {
        val item = mealDao.getItemById(itemId) ?: return@runCatchingApp
        mealDao.deleteItemById(itemId)
        if (mealDao.countItemsFor(item.mealLogId) == 0) {
            mealDao.deleteById(item.mealLogId)
        } else {
            touch(item.mealLogId)
        }
    }

    suspend fun deleteLog(id: Long): Outcome<Unit> = runCatchingApp { mealDao.deleteById(id) }

    /**
     * "Copy yesterday" (§4.2 Nutrition diary): every log of [fromDay] is re-inserted on [toDay]
     * with its item snapshots **verbatim**, so the copy shows the same numbers as the original
     * even if an ingredient has changed since.
     */
    suspend fun copyDay(fromDay: Long, toDay: Long): Outcome<Unit> = runCatchingApp {
        val logs = mealDao.getDayFull(fromDay)
        val now = clock.millis()
        logs.forEach { source ->
            val newId = mealDao.upsert(
                source.log.copy(id = 0L, day = toDay, createdAtMillis = now, updatedAtMillis = now),
            )
            if (source.items.isNotEmpty()) {
                mealDao.upsertItems(source.items.map { it.copy(id = 0L, mealLogId = newId) })
            }
        }
    }

    // ---- snapshotting ---------------------------------------------------------------------------

    private fun snapshot(
        logId: Long,
        input: MealItemInput,
        entity: IngredientEntity,
    ): MealLogItemEntity {
        val ingredient = entity.toDomain()
        val totals: MacroTotals = MealMath
            .nutrientsFor(MealQuantity(input.quantity, input.unit), ingredient)
            .totals
        return MealLogItemEntity(
            mealLogId = logId,
            ingredientId = ingredient.id,
            nameSnapshot = ingredient.name,
            quantity = input.quantity,
            unit = input.unit,
            kcal = totals.kcal,
            proteinG = totals.proteinG,
            carbsG = totals.carbsG,
            sugarG = totals.sugarG,
            fatG = totals.fatG,
            satFatG = totals.satFatG,
            fiberG = totals.fiberG,
            saltG = totals.saltG,
        )
    }

    /** Proportional rescale of an orphaned snapshot (the ingredient is gone). */
    private fun MealLogItemEntity.scaledTo(quantity: Double, unit: QuantityUnit): MealLogItemEntity {
        val ratio = if (this.quantity != 0.0) quantity / this.quantity else 0.0
        return copy(
            quantity = quantity,
            unit = unit,
            kcal = kcal * ratio,
            proteinG = proteinG * ratio,
            carbsG = carbsG * ratio,
            sugarG = sugarG * ratio,
            fatG = fatG * ratio,
            satFatG = satFatG * ratio,
            fiberG = fiberG * ratio,
            saltG = saltG * ratio,
        )
    }

    private suspend fun touch(logId: Long) {
        val log = mealDao.getById(logId) ?: return
        mealDao.upsert(log.copy(updatedAtMillis = clock.millis()))
    }
}
