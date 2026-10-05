package com.myhealth.domain.repository

import com.myhealth.domain.model.MealLog
import com.myhealth.domain.model.MealLogSummary
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * What the user picked before nutrients are resolved: an ingredient and an amount. The repository
 * turns each entry into a denormalised `meal_log_item` via `MealMath` (§3.7, P4.1) at log time —
 * that snapshot is what makes editing an ingredient later leave past logs untouched (P4.5).
 */
data class MealItemInput(val ingredientId: Long, val quantity: Double, val unit: QuantityUnit)

/**
 * `meal_log`/`meal_log_item` and `meal_template`/`meal_template_item` (PLAN §2.2.5, P4.4/P4.5).
 */
interface MealRepository {

    fun observeDay(day: Long): Flow<List<MealLog>>

    fun observeRange(fromDay: Long, toDay: Long): Flow<List<MealLogSummary>>

    suspend fun getLog(id: Long): MealLog?

    /** Snapshots nutrients for every item; returns the new `meal_log` id. */
    suspend fun logMeal(
        day: Long,
        slot: MealSlot,
        items: List<MealItemInput>,
        name: String? = null,
        atMinuteOfDay: Int? = null,
        templateId: Long? = null,
    ): Outcome<Long>

    /** Re-snapshots the item from its ingredient at the new quantity. */
    suspend fun updateItemQuantity(itemId: Long, quantity: Double, unit: QuantityUnit): Outcome<Unit>

    suspend fun deleteItem(itemId: Long): Outcome<Unit>

    suspend fun deleteLog(id: Long): Outcome<Unit>

    /** "Copy yesterday" — copies each log of [fromDay] onto [toDay] with its item snapshots
     * verbatim, so the copy reads exactly like the day it came from (P4.5). */
    suspend fun copyDay(fromDay: Long, toDay: Long): Outcome<Unit>

    // ---- templates -----------------------------------------------------------------------------

    fun observeTemplates(): Flow<List<MealTemplate>>

    suspend fun getTemplate(id: Long): MealTemplate?

    suspend fun upsertTemplate(template: MealTemplate): Outcome<Long>

    suspend fun deleteTemplate(id: Long): Outcome<Unit>

    /** "Log now": copies the template's items into a `meal_log`, snapshotting nutrients. */
    suspend fun logTemplate(templateId: Long, day: Long, slot: MealSlot): Outcome<Long>
}
