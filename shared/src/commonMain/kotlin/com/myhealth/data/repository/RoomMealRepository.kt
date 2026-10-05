package com.myhealth.data.repository

import com.myhealth.data.db.dao.IngredientDao
import com.myhealth.data.db.dao.MealDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.data.mapper.toSummary
import com.myhealth.domain.model.MealLog
import com.myhealth.domain.model.MealLogSummary
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.repository.MealItemInput
import com.myhealth.domain.repository.MealRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.myhealth.data.time.PlatformClock

/**
 * Room-backed [MealRepository] (PLAN §2.2.5, P4.4 templates + P4.5 logs) over [MealDao].
 *
 * Reads are plain `Flow` mappings of the `@Transaction` relation queries; every write that has to
 * snapshot nutrients is delegated to [MealLogWriter], which owns the §3.7 math and the
 * snapshot-on-log rule. [IngredientDao] is needed on the write path only — to resolve the
 * ingredient a logged item is priced against and to bump its `useCount`/`lastUsedAtMillis`.
 */
class RoomMealRepository(
    private val mealDao: MealDao,
    ingredientDao: IngredientDao,
    private val clock: PlatformClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MealRepository {

    private val writer = MealLogWriter(mealDao, ingredientDao, clock)

    // ---- logs ------------------------------------------------------------------------------------

    override fun observeDay(day: Long): Flow<List<MealLog>> =
        mealDao.observeDay(day).map { rows -> rows.map { it.toDomain() } }

    /** One [MealLogSummary] per logged meal in the range; a day's numbers are the sum of its rows
     * (the item snapshots are already absolute, so no ingredient lookup is involved). */
    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<MealLogSummary>> =
        mealDao.observeRange(fromDay, toDay).map { rows -> rows.map { it.toSummary() } }

    override suspend fun getLog(id: Long): MealLog? =
        withContext(ioDispatcher) { mealDao.getFullById(id)?.toDomain() }

    override suspend fun logMeal(
        day: Long,
        slot: MealSlot,
        items: List<MealItemInput>,
        name: String?,
        atMinuteOfDay: Int?,
        templateId: Long?,
    ): Outcome<Long> = withContext(ioDispatcher) {
        writer.logMeal(day, slot, items, name, atMinuteOfDay, templateId)
    }

    override suspend fun updateItemQuantity(
        itemId: Long,
        quantity: Double,
        unit: QuantityUnit,
    ): Outcome<Unit> = withContext(ioDispatcher) { writer.updateItemQuantity(itemId, quantity, unit) }

    override suspend fun deleteItem(itemId: Long): Outcome<Unit> =
        withContext(ioDispatcher) { writer.deleteItem(itemId) }

    override suspend fun deleteLog(id: Long): Outcome<Unit> =
        withContext(ioDispatcher) { writer.deleteLog(id) }

    override suspend fun copyDay(fromDay: Long, toDay: Long): Outcome<Unit> =
        withContext(ioDispatcher) { writer.copyDay(fromDay, toDay) }

    // ---- templates -------------------------------------------------------------------------------

    override fun observeTemplates(): Flow<List<MealTemplate>> =
        mealDao.observeTemplates().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getTemplate(id: Long): MealTemplate? =
        withContext(ioDispatcher) { mealDao.getTemplateWithItems(id)?.toDomain() }

    /**
     * Inserts or replaces a template and its item rows in one go: the incoming [MealTemplate.items]
     * are the full desired set, so the old rows are dropped and re-inserted with `sortOrder` taken
     * from the list order (the editor's row order is the template's order).
     */
    override suspend fun upsertTemplate(template: MealTemplate): Outcome<Long> = withContext(ioDispatcher) {
        if (template.name.isBlank()) {
            return@withContext Outcome.Err(AppError.Validation("name", "Name is required."))
        }
        if (template.items.any { it.quantity <= 0.0 }) {
            return@withContext Outcome.Err(AppError.Validation("quantity", "Every item needs a quantity."))
        }
        runCatchingApp {
            val now = clock.millis()
            val entity = template.toEntity().let {
                it.copy(
                    updatedAtMillis = now,
                    createdAtMillis = if (it.id == 0L) now else it.createdAtMillis,
                )
            }
            val id = mealDao.upsertTemplate(entity)
            mealDao.deleteTemplateItemsFor(id)
            if (template.items.isNotEmpty()) {
                mealDao.upsertTemplateItems(
                    template.items.mapIndexed { index, item ->
                        item.toEntity().copy(id = 0L, templateId = id, sortOrder = index)
                    },
                )
            }
            id
        }
    }

    override suspend fun deleteTemplate(id: Long): Outcome<Unit> = withContext(ioDispatcher) {
        // `meal_template_item` is CASCADE; `meal_log.templateId` is SET_NULL, so logged history
        // survives the template it came from (§2.2.5).
        runCatchingApp { mealDao.deleteTemplateById(id) }
    }

    override suspend fun logTemplate(templateId: Long, day: Long, slot: MealSlot): Outcome<Long> =
        withContext(ioDispatcher) { writer.logTemplate(templateId, day, slot) }
}
