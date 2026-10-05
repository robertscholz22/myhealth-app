package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.myhealth.data.db.entity.MealLogEntity
import com.myhealth.data.db.entity.MealLogItemEntity
import com.myhealth.data.db.entity.MealTemplateEntity
import com.myhealth.data.db.entity.MealTemplateItemEntity
import com.myhealth.data.db.relation.MealLogWithItems
import com.myhealth.data.db.relation.MealTemplateWithItems
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `meal_log` / `meal_log_item` and `meal_template` / `meal_template_item` (PLAN §2.2.5).
 * Relation queries are `@Transaction` so a log and its items are always read consistently.
 */
@Dao
interface MealDao {

    @Upsert
    suspend fun upsert(entity: MealLogEntity): Long

    @Query("SELECT * FROM meal_log WHERE id = :id")
    suspend fun getById(id: Long): MealLogEntity?

    @Query("DELETE FROM meal_log WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Transaction
    @Query("SELECT * FROM meal_log WHERE day = :day ORDER BY atMinuteOfDay ASC, id ASC")
    fun observeDay(day: Long): Flow<List<MealLogWithItems>>

    @Transaction
    @Query("SELECT * FROM meal_log WHERE day BETWEEN :fromDay AND :toDay ORDER BY day ASC, atMinuteOfDay ASC")
    fun observeRange(fromDay: Long, toDay: Long): Flow<List<MealLogWithItems>>

    @Transaction
    @Query("SELECT * FROM meal_log WHERE id = :id")
    suspend fun getFullById(id: Long): MealLogWithItems?

    @Transaction
    @Query("SELECT * FROM meal_log WHERE day = :day ORDER BY atMinuteOfDay ASC, id ASC")
    suspend fun getDayFull(day: Long): List<MealLogWithItems>

    @Upsert
    suspend fun upsertItems(items: List<MealLogItemEntity>)

    @Upsert
    suspend fun upsertItem(item: MealLogItemEntity): Long

    @Query("SELECT * FROM meal_log_item WHERE id = :id")
    suspend fun getItemById(id: Long): MealLogItemEntity?

    @Query("SELECT COUNT(*) FROM meal_log_item WHERE mealLogId = :mealLogId")
    suspend fun countItemsFor(mealLogId: Long): Int

    @Query("DELETE FROM meal_log_item WHERE mealLogId = :mealLogId")
    suspend fun deleteItemsFor(mealLogId: Long)

    @Query("DELETE FROM meal_log_item WHERE id = :id")
    suspend fun deleteItemById(id: Long)

    // ---- templates ----------------------------------------------------------------------------

    @Upsert
    suspend fun upsertTemplate(entity: MealTemplateEntity): Long

    @Query("SELECT * FROM meal_template WHERE id = :id")
    suspend fun getTemplateById(id: Long): MealTemplateEntity?

    @Query("DELETE FROM meal_template WHERE id = :id")
    suspend fun deleteTemplateById(id: Long)

    @Transaction
    @Query("SELECT * FROM meal_template WHERE archived = 0 ORDER BY isFavorite DESC, useCount DESC, name ASC")
    fun observeTemplates(): Flow<List<MealTemplateWithItems>>

    @Transaction
    @Query("SELECT * FROM meal_template WHERE id = :id")
    suspend fun getTemplateWithItems(id: Long): MealTemplateWithItems?

    @Upsert
    suspend fun upsertTemplateItems(items: List<MealTemplateItemEntity>)

    @Query("DELETE FROM meal_template_item WHERE templateId = :templateId")
    suspend fun deleteTemplateItemsFor(templateId: Long)

    // ---- ingredient reference counts (P4.2: archive-vs-delete branching) ----------------------

    @Query("SELECT COUNT(*) FROM meal_log_item WHERE ingredientId = :ingredientId")
    suspend fun countLogItemsByIngredient(ingredientId: Long): Int

    @Query("SELECT COUNT(*) FROM meal_template_item WHERE ingredientId = :ingredientId")
    suspend fun countTemplateItemsByIngredient(ingredientId: Long): Int
}
