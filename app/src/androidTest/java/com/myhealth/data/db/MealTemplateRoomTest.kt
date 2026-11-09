package com.myhealth.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.entity.IngredientEntity
import com.myhealth.data.repository.RoomMealRepository
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.MealTemplateItem
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * BUG-22: saving an edited meal template against the real Room database. `@Upsert` returns `-1`
 * when it updates an existing row instead of inserting one — the unit tests' fake DAO returned the
 * id, so only a real database shows what the editor's "Save" did on the phone. P24's servings
 * ride on the same save and on `logTemplate`'s scaling, so they are checked here too.
 */
@RunWith(AndroidJUnit4::class)
class MealTemplateRoomTest {

    private lateinit var db: MyHealthDatabase
    private lateinit var repo: RoomMealRepository
    private val clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyHealthDatabase::class.java,
        ).build()
        repo = RoomMealRepository(db.mealDao(), db.ingredientDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun bug22_an_edited_template_saves_its_new_items_and_fields() = runTest {
        val oats = ingredient("Oats")
        val milk = ingredient("Milk")
        val id = (repo.upsertTemplate(template(0L, "Porridge", listOf(item(oats, 60.0)))) as Outcome.Ok).value

        val saved = repo.upsertTemplate(
            template(id, "Porridge with milk", listOf(item(oats, 80.0), item(milk, 200.0))),
        )

        assertThat(saved).isEqualTo(Outcome.Ok(id))
        val loaded = repo.getTemplate(id)!!
        assertThat(loaded.name).isEqualTo("Porridge with milk")
        assertThat(loaded.items.sortedBy { it.sortOrder }.map { it.ingredientId to it.quantity })
            .containsExactly(oats to 80.0, milk to 200.0)
            .inOrder()
        assertThat(db.mealDao().getTemplateWithItems(id)!!.items.map { it.templateId }).containsExactly(id, id)
    }

    @Test
    fun bug22_starring_a_template_keeps_its_items() = runTest {
        val oats = ingredient("Oats")
        val id = (repo.upsertTemplate(template(0L, "Porridge", listOf(item(oats, 60.0)))) as Outcome.Ok).value
        val stored = repo.getTemplate(id)!!

        assertThat(repo.upsertTemplate(stored.copy(isFavorite = true))).isEqualTo(Outcome.Ok(id))

        val loaded = repo.getTemplate(id)!!
        assertThat(loaded.isFavorite).isTrue()
        assertThat(loaded.items.map { it.quantity }).containsExactly(60.0)
    }

    @Test
    fun p24_servings_save_with_an_edit_and_scale_what_gets_logged() = runTest {
        val oats = ingredient("Oats")
        val id = (repo.upsertTemplate(template(0L, "Porridge", listOf(item(oats, 60.0)))) as Outcome.Ok).value
        val stored = repo.getTemplate(id)!!
        assertThat(stored.servings).isEqualTo(1.0)

        // The owner's case: the recipe is edited to make four servings out of 400 g.
        assertThat(repo.upsertTemplate(stored.copy(servings = 4.0, items = listOf(item(oats, 400.0)))))
            .isEqualTo(Outcome.Ok(id))
        val day = 20_401L
        assertThat(repo.logTemplate(id, day, MealSlot.BREAKFAST, servings = 1.5)).isInstanceOf(Outcome.Ok::class.java)

        val logged = db.mealDao().getDayFull(day).single().items.single()
        assertThat(logged.quantity).isWithin(1e-9).of(150.0)
        // 100 kcal per 100 g.
        assertThat(logged.kcal).isWithin(1e-9).of(150.0)
        val after = repo.getTemplate(id)!!
        assertThat(after.servings).isEqualTo(4.0)
        assertThat(after.useCount).isEqualTo(1)
        assertThat(after.items.single().quantity).isEqualTo(400.0)
    }

    private suspend fun ingredient(name: String): Long = db.ingredientDao().upsert(
        IngredientEntity(
            name = name,
            basis = MeasureBasis.PER_100G,
            kcal = 100.0,
            proteinG = 5.0,
            carbsG = 10.0,
            fatG = 2.0,
            source = "MANUAL",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        ),
    )

    private fun item(ingredientId: Long, grams: Double) = MealTemplateItem(
        id = 0L,
        templateId = 0L,
        ingredientId = ingredientId,
        quantity = grams,
        unit = QuantityUnit.G,
        sortOrder = 0,
    )

    private fun template(id: Long, name: String, items: List<MealTemplateItem>) = MealTemplate(
        id = id,
        name = name,
        defaultSlot = MealSlot.BREAKFAST,
        note = null,
        isFavorite = false,
        useCount = 0,
        lastUsedAtMillis = null,
        archived = false,
        items = items,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )
}
