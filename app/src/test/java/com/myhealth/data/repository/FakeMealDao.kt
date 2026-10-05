package com.myhealth.data.repository

import com.myhealth.data.db.dao.IngredientDao
import com.myhealth.data.db.dao.MealDao
import com.myhealth.data.db.entity.IngredientEntity
import com.myhealth.data.db.entity.MealLogEntity
import com.myhealth.data.db.entity.MealLogItemEntity
import com.myhealth.data.db.entity.MealTemplateEntity
import com.myhealth.data.db.entity.MealTemplateItemEntity
import com.myhealth.data.db.relation.MealLogWithItems
import com.myhealth.data.db.relation.MealTemplateWithItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * In-memory [MealDao] for the P4.4/P4.5 repository tests: reproduces the ordering of the real SQL
 * queries and the two foreign keys that matter — `meal_log_item` is `CASCADE` on its log and
 * `meal_template_item` is `CASCADE` on its template.
 */
internal class FakeMealDao : MealDao {

    val logs = MutableStateFlow<List<MealLogEntity>>(emptyList())
    val items = MutableStateFlow<List<MealLogItemEntity>>(emptyList())
    val templates = MutableStateFlow<List<MealTemplateEntity>>(emptyList())
    val templateItems = MutableStateFlow<List<MealTemplateItemEntity>>(emptyList())

    private var nextLogId = 1L
    private var nextItemId = 1L
    private var nextTemplateId = 1L
    private var nextTemplateItemId = 1L

    override suspend fun upsert(entity: MealLogEntity): Long {
        val id = if (entity.id == 0L) nextLogId++ else entity.id
        logs.value = logs.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override suspend fun getById(id: Long): MealLogEntity? = logs.value.firstOrNull { it.id == id }

    override suspend fun deleteById(id: Long) {
        logs.value = logs.value.filterNot { it.id == id }
        items.value = items.value.filterNot { it.mealLogId == id }
    }

    override fun observeDay(day: Long): Flow<List<MealLogWithItems>> =
        combine(logs, items) { logRows, itemRows ->
            logRows.filter { it.day == day }.sortedWith(compareBy({ it.atMinuteOfDay }, { it.id }))
                .map { log -> MealLogWithItems(log, itemRows.filter { it.mealLogId == log.id }) }
        }

    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<MealLogWithItems>> =
        combine(logs, items) { logRows, itemRows ->
            logRows.filter { it.day in fromDay..toDay }
                .sortedWith(compareBy({ it.day }, { it.atMinuteOfDay }))
                .map { log -> MealLogWithItems(log, itemRows.filter { it.mealLogId == log.id }) }
        }

    override suspend fun getFullById(id: Long): MealLogWithItems? =
        logs.value.firstOrNull { it.id == id }
            ?.let { log -> MealLogWithItems(log, items.value.filter { it.mealLogId == log.id }) }

    override suspend fun getDayFull(day: Long): List<MealLogWithItems> =
        logs.value.filter { it.day == day }.sortedWith(compareBy({ it.atMinuteOfDay }, { it.id }))
            .map { log -> MealLogWithItems(log, items.value.filter { it.mealLogId == log.id }) }

    override suspend fun upsertItems(items: List<MealLogItemEntity>) {
        items.forEach { upsertItem(it) }
    }

    override suspend fun upsertItem(item: MealLogItemEntity): Long {
        val id = if (item.id == 0L) nextItemId++ else item.id
        items.value = items.value.filterNot { it.id == id } + item.copy(id = id)
        return id
    }

    override suspend fun getItemById(id: Long): MealLogItemEntity? = items.value.firstOrNull { it.id == id }

    override suspend fun countItemsFor(mealLogId: Long): Int = items.value.count { it.mealLogId == mealLogId }

    override suspend fun deleteItemsFor(mealLogId: Long) {
        items.value = items.value.filterNot { it.mealLogId == mealLogId }
    }

    override suspend fun deleteItemById(id: Long) {
        items.value = items.value.filterNot { it.id == id }
    }

    override suspend fun upsertTemplate(entity: MealTemplateEntity): Long {
        val id = if (entity.id == 0L) nextTemplateId++ else entity.id
        templates.value = templates.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override suspend fun getTemplateById(id: Long): MealTemplateEntity? =
        templates.value.firstOrNull { it.id == id }

    override suspend fun deleteTemplateById(id: Long) {
        templates.value = templates.value.filterNot { it.id == id }
        templateItems.value = templateItems.value.filterNot { it.templateId == id }
    }

    override fun observeTemplates(): Flow<List<MealTemplateWithItems>> =
        combine(templates, templateItems) { rows, itemRows ->
            rows.filterNot { it.archived }
                .sortedWith(
                    compareByDescending<MealTemplateEntity> { it.isFavorite }
                        .thenByDescending { it.useCount }
                        .thenBy { it.name },
                )
                .map { tpl -> MealTemplateWithItems(tpl, itemRows.filter { it.templateId == tpl.id }) }
        }

    override suspend fun getTemplateWithItems(id: Long): MealTemplateWithItems? =
        templates.value.firstOrNull { it.id == id }
            ?.let { tpl -> MealTemplateWithItems(tpl, templateItems.value.filter { it.templateId == tpl.id }) }

    override suspend fun upsertTemplateItems(items: List<MealTemplateItemEntity>) {
        items.forEach { item ->
            val id = if (item.id == 0L) nextTemplateItemId++ else item.id
            templateItems.value = templateItems.value.filterNot { it.id == id } + item.copy(id = id)
        }
    }

    override suspend fun deleteTemplateItemsFor(templateId: Long) {
        templateItems.value = templateItems.value.filterNot { it.templateId == templateId }
    }

    override suspend fun countLogItemsByIngredient(ingredientId: Long): Int =
        items.value.count { it.ingredientId == ingredientId }

    override suspend fun countTemplateItemsByIngredient(ingredientId: Long): Int =
        templateItems.value.count { it.ingredientId == ingredientId }
}

/** In-memory [IngredientDao] for the meal tests (the P4.2 test's own fake is file-private). */
internal class FakeMealIngredientDao : IngredientDao {

    val rows = MutableStateFlow<List<IngredientEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun upsert(entity: IngredientEntity): Long {
        val id = if (entity.id == 0L) nextId++ else entity.id
        rows.value = rows.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override suspend fun getById(id: Long): IngredientEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun getByIds(ids: List<Long>): List<IngredientEntity> = rows.value.filter { it.id in ids }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override fun search(q: String, limit: Int): Flow<List<IngredientEntity>> = rows.map { list ->
        list.filter { !it.archived && it.name.contains(q, ignoreCase = true) }.take(limit)
    }

    override fun searchFts(match: String, limit: Int): Flow<List<IngredientEntity>> = rows.map { list ->
        val terms = match.split(" ").map { it.removeSuffix("*") }.filter { it.isNotEmpty() }
        list.filter { row -> !row.archived && terms.all { row.name.contains(it, ignoreCase = true) } }
            .take(limit)
    }

    override fun observeRecent(limit: Int): Flow<List<IngredientEntity>> = rows.map { list ->
        list.filter { !it.archived && it.lastUsedAtMillis != null }
            .sortedByDescending { it.lastUsedAtMillis }.take(limit)
    }

    override fun observeAllActive(limit: Int): Flow<List<IngredientEntity>> = rows.map { list ->
        list.filter { !it.archived }
            .sortedWith(
                compareBy<IngredientEntity> { it.lastUsedAtMillis == null }
                    .thenByDescending { it.lastUsedAtMillis ?: Long.MIN_VALUE }
                    .thenBy { it.name },
            )
            .take(limit)
    }

    override fun observeFavorites(): Flow<List<IngredientEntity>> =
        rows.map { list -> list.filter { !it.archived && it.isFavorite }.sortedBy { it.name } }

    override fun observeArchived(limit: Int): Flow<List<IngredientEntity>> =
        rows.map { list -> list.filter { it.archived }.sortedBy { it.name }.take(limit) }

    override suspend fun getByBarcode(barcode: String): IngredientEntity? =
        rows.value.firstOrNull { it.barcode == barcode }

    override suspend fun markUsed(id: Long, atMillis: Long) {
        rows.value = rows.value.map {
            if (it.id == id) {
                it.copy(useCount = it.useCount + 1, lastUsedAtMillis = atMillis, updatedAtMillis = atMillis)
            } else {
                it
            }
        }
    }
}
