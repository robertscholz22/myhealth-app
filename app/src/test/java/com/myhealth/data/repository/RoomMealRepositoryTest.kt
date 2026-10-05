package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.entity.IngredientEntity
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.MealTemplateItem
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.repository.MealItemInput
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [RoomMealRepository] / [MealLogWriter] (PLAN P4.4/P4.5) against the in-memory
 * [FakeMealDao] + [FakeMealIngredientDao].
 *
 * The headline case is `meal07` of §3.7: the denormalised snapshot in `meal_log_item` means an
 * ingredient edit never rewrites a logged day.
 */
class RoomMealRepositoryTest {

    private val mealDao = FakeMealDao()
    private val ingredientDao = FakeMealIngredientDao()
    private val clock = Fixtures.fixedClock("2026-09-12T12:00:00Z")
    private val repo = RoomMealRepository(mealDao, ingredientDao, clock, Dispatchers.Unconfined)
    private val day = Fixtures.epochDay("2026-09-12")

    @Test
    fun meal07_editing_ingredient_does_not_change_past_logs() = runTest {
        val id = putIngredient(name = "Rolled oats", kcal = 370.0, protein = 13.0)
        val logId = logOats(ingredientId = id, grams = 100.0)

        // 370 kcal per 100 g -> 100 g logged as 370 kcal / 13 g protein.
        val before = repo.observeDay(day).first().single().items.single()
        assertThat(before.kcal).isWithin(1e-9).of(370.0)
        assertThat(before.proteinG).isWithin(1e-9).of(13.0)

        // The ingredient is corrected long after the fact.
        ingredientDao.upsert(ingredientDao.getById(id)!!.copy(kcal = 1.0, proteinG = 0.0, name = "Oats (fixed)"))

        val after = repo.observeDay(day).first().single().items.single()
        assertThat(after.kcal).isWithin(1e-9).of(370.0)
        assertThat(after.proteinG).isWithin(1e-9).of(13.0)
        assertThat(after.nameSnapshot).isEqualTo("Rolled oats")
        assertThat(repo.getLog(logId)!!.items.single().kcal).isWithin(1e-9).of(370.0)
    }

    @Test
    fun log_meal_snapshots_absolute_nutrients_and_marks_the_ingredient_used() = runTest {
        val id = putIngredient(name = "Rolled oats", kcal = 370.0, protein = 13.0)

        logOats(ingredientId = id, grams = 250.0)

        val item = mealDao.items.value.single()
        assertThat(item.kcal).isWithin(1e-9).of(925.0)
        assertThat(item.proteinG).isWithin(1e-9).of(32.5)
        val stored = ingredientDao.getById(id)!!
        assertThat(stored.useCount).isEqualTo(1)
        assertThat(stored.lastUsedAtMillis).isEqualTo(clock.millis())
    }

    @Test
    fun log_meal_rejects_an_unknown_ingredient() = runTest {
        val result = repo.logMeal(day, MealSlot.LUNCH, listOf(MealItemInput(42L, 100.0, QuantityUnit.G)))

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat(mealDao.logs.value).isEmpty()
    }

    @Test
    fun update_item_quantity_re_snapshots_from_the_current_ingredient() = runTest {
        val id = putIngredient(name = "Rolled oats", kcal = 370.0, protein = 13.0)
        logOats(ingredientId = id, grams = 100.0)
        val itemId = mealDao.items.value.single().id
        ingredientDao.upsert(ingredientDao.getById(id)!!.copy(kcal = 400.0))

        val result = repo.updateItemQuantity(itemId, quantity = 50.0, unit = QuantityUnit.G)

        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        val item = mealDao.items.value.single()
        assertThat(item.quantity).isWithin(1e-9).of(50.0)
        assertThat(item.kcal).isWithin(1e-9).of(200.0)
        assertThat(item.proteinG).isWithin(1e-9).of(6.5)
    }

    @Test
    fun update_item_quantity_rescales_the_snapshot_when_the_ingredient_is_gone() = runTest {
        val id = putIngredient(name = "Rolled oats", kcal = 370.0, protein = 13.0)
        logOats(ingredientId = id, grams = 100.0)
        val itemId = mealDao.items.value.single().id
        ingredientDao.deleteById(id)

        repo.updateItemQuantity(itemId, quantity = 250.0, unit = QuantityUnit.G)

        val item = mealDao.items.value.single()
        assertThat(item.quantity).isWithin(1e-9).of(250.0)
        assertThat(item.kcal).isWithin(1e-9).of(925.0)
        assertThat(item.proteinG).isWithin(1e-9).of(32.5)
        assertThat(item.nameSnapshot).isEqualTo("Rolled oats")
    }

    @Test
    fun copy_day_duplicates_logs_and_item_snapshots_verbatim() = runTest {
        val id = putIngredient(name = "Rolled oats", kcal = 370.0, protein = 13.0)
        logOats(ingredientId = id, grams = 100.0)
        // Even after the ingredient changes, the copy must read like the original day.
        ingredientDao.upsert(ingredientDao.getById(id)!!.copy(kcal = 10.0))

        val result = repo.copyDay(day, day + 1)

        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        val copied = repo.observeDay(day + 1).first().single()
        assertThat(copied.day).isEqualTo(day + 1)
        assertThat(copied.slot).isEqualTo(MealSlot.BREAKFAST)
        val item = copied.items.single()
        assertThat(item.kcal).isWithin(1e-9).of(370.0)
        assertThat(item.quantity).isWithin(1e-9).of(100.0)
        assertThat(repo.observeDay(day).first().single().items).hasSize(1)
    }

    @Test
    fun delete_item_removes_the_meal_once_it_is_empty() = runTest {
        val id = putIngredient(name = "Rolled oats", kcal = 370.0, protein = 13.0)
        logOats(ingredientId = id, grams = 100.0)
        val itemId = mealDao.items.value.single().id

        repo.deleteItem(itemId)

        assertThat(mealDao.items.value).isEmpty()
        assertThat(mealDao.logs.value).isEmpty()
    }

    @Test
    fun observe_range_summarises_each_logged_meal() = runTest {
        val id = putIngredient(name = "Rolled oats", kcal = 370.0, protein = 13.0)
        logOats(ingredientId = id, grams = 100.0)
        repo.logMeal(day + 1, MealSlot.DINNER, listOf(MealItemInput(id, 200.0, QuantityUnit.G)))

        val summaries = repo.observeRange(day, day + 1).first()

        assertThat(summaries).hasSize(2)
        assertThat(summaries.map { it.day }).containsExactly(day, day + 1).inOrder()
        assertThat(summaries.last().totals.kcal).isWithin(1e-9).of(740.0)
    }

    @Test
    fun upsert_template_replaces_its_items_and_renumbers_sort_order() = runTest {
        val oats = putIngredient(name = "Rolled oats", kcal = 370.0, protein = 13.0)
        val milk = putIngredient(name = "Milk", kcal = 64.0, protein = 3.4)
        val id = (repo.upsertTemplate(template(items = listOf(item(oats, 80.0)))) as Outcome.Ok).value

        val stored = repo.getTemplate(id)!!
        repo.upsertTemplate(
            stored.copy(items = listOf(item(milk, 200.0, QuantityUnit.ML), item(oats, 60.0))),
        )

        val reloaded = repo.getTemplate(id)!!
        assertThat(reloaded.items.map { it.ingredientId }).containsExactly(milk, oats).inOrder()
        assertThat(reloaded.items.map { it.sortOrder }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun upsert_template_rejects_a_blank_name() = runTest {
        val result = repo.upsertTemplate(template(name = "  "))

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat(mealDao.templates.value).isEmpty()
    }

    @Test
    fun log_template_snapshots_its_items_and_bumps_use_count() = runTest {
        val oats = putIngredient(name = "Rolled oats", kcal = 370.0, protein = 13.0)
        val id = (repo.upsertTemplate(template(items = listOf(item(oats, 80.0)))) as Outcome.Ok).value

        val result = repo.logTemplate(id, day, MealSlot.BREAKFAST)

        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        val log = repo.observeDay(day).first().single()
        assertThat(log.name).isEqualTo("Oatmeal")
        assertThat(log.templateId).isEqualTo(id)
        assertThat(log.items.single().kcal).isWithin(1e-9).of(296.0)
        assertThat(mealDao.templates.value.single().useCount).isEqualTo(1)
        assertThat(mealDao.templates.value.single().lastUsedAtMillis).isEqualTo(clock.millis())
    }

    @Test
    fun log_template_rejects_a_template_without_items() = runTest {
        val id = (repo.upsertTemplate(template(items = emptyList())) as Outcome.Ok).value

        val result = repo.logTemplate(id, day, MealSlot.LUNCH)

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat(mealDao.logs.value).isEmpty()
    }

    @Test
    fun delete_template_keeps_the_meals_already_logged_from_it() = runTest {
        val oats = putIngredient(name = "Rolled oats", kcal = 370.0, protein = 13.0)
        val id = (repo.upsertTemplate(template(items = listOf(item(oats, 80.0)))) as Outcome.Ok).value
        repo.logTemplate(id, day, MealSlot.BREAKFAST)

        repo.deleteTemplate(id)

        assertThat(mealDao.templates.value).isEmpty()
        assertThat(mealDao.templateItems.value).isEmpty()
        assertThat(repo.observeDay(day).first().single().items.single().kcal).isWithin(1e-9).of(296.0)
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private suspend fun logOats(ingredientId: Long, grams: Double): Long {
        val result = repo.logMeal(
            day = day,
            slot = MealSlot.BREAKFAST,
            items = listOf(MealItemInput(ingredientId, grams, QuantityUnit.G)),
        )
        return (result as Outcome.Ok).value
    }

    private suspend fun putIngredient(name: String, kcal: Double, protein: Double): Long =
        ingredientDao.upsert(
            IngredientEntity(
                id = 0L,
                name = name,
                brand = null,
                barcode = null,
                basis = MeasureBasis.PER_100G,
                pieceGrams = null,
                servingGrams = null,
                servingLabel = null,
                kcal = kcal,
                proteinG = protein,
                carbsG = 60.0,
                sugarG = null,
                fatG = 7.0,
                satFatG = null,
                fiberG = null,
                saltG = null,
                sodiumG = null,
                isFavorite = false,
                source = "MANUAL",
                offProductJson = null,
                lastUsedAtMillis = null,
                useCount = 0,
                archived = false,
                createdAtMillis = 0L,
                updatedAtMillis = 0L,
            ),
        )

    private fun template(name: String = "Oatmeal", items: List<MealTemplateItem> = emptyList()) = MealTemplate(
        id = 0L,
        name = name,
        defaultSlot = MealSlot.BREAKFAST,
        note = null,
        isFavorite = false,
        useCount = 0,
        lastUsedAtMillis = null,
        archived = false,
        items = items,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    private fun item(ingredientId: Long, quantity: Double, unit: QuantityUnit = QuantityUnit.G) =
        MealTemplateItem(
            id = 0L,
            templateId = 0L,
            ingredientId = ingredientId,
            quantity = quantity,
            unit = unit,
            sortOrder = 0,
        )
}
