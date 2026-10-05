package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.dao.IngredientDao
import com.myhealth.data.db.dao.MealDao
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [RoomIngredientRepository] (PLAN P4.2) against an in-memory fake [IngredientDao]
 * and a [MealDao] mock that only stubs the reference-count queries [deleteOrArchive] needs.
 */
class RoomIngredientRepositoryTest {

    private val dao = FakeIngredientDao()
    private val mealDao = mockk<MealDao>()
    private val clock = Fixtures.fixedClock("2026-09-12T12:00:00Z")
    private val repo = RoomIngredientRepository(dao, mealDao, clock, Dispatchers.Unconfined)

    @Test
    fun mark_used_bumps_use_count_and_last_used() = runTest {
        val id = insert(name = "Oats")

        repo.markUsed(id)
        repo.markUsed(id)

        val stored = dao.rows.value.single { it.id == id }
        assertThat(stored.useCount).isEqualTo(2)
        assertThat(stored.lastUsedAtMillis).isEqualTo(clock.millis())
    }

    @Test
    fun delete_or_archive_hard_deletes_when_unreferenced() = runTest {
        val id = insert(name = "Banana")
        coEvery { mealDao.countLogItemsByIngredient(id) } returns 0
        coEvery { mealDao.countTemplateItemsByIngredient(id) } returns 0

        val result = repo.deleteOrArchive(id)

        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        assertThat(dao.rows.value).isEmpty()
    }

    @Test
    fun delete_or_archive_archives_when_referenced_by_a_meal_log_item() = runTest {
        val id = insert(name = "Banana")
        coEvery { mealDao.countLogItemsByIngredient(id) } returns 1
        coEvery { mealDao.countTemplateItemsByIngredient(id) } returns 0

        repo.deleteOrArchive(id)

        val stored = dao.rows.value.single { it.id == id }
        assertThat(stored.archived).isTrue()
    }

    @Test
    fun delete_or_archive_archives_when_referenced_by_a_meal_template_item() = runTest {
        val id = insert(name = "Banana")
        coEvery { mealDao.countLogItemsByIngredient(id) } returns 0
        coEvery { mealDao.countTemplateItemsByIngredient(id) } returns 1

        repo.deleteOrArchive(id)

        val stored = dao.rows.value.single { it.id == id }
        assertThat(stored.archived).isTrue()
    }

    @Test
    fun search_with_a_blank_query_orders_by_last_used_instead_of_scanning() = runTest {
        insert(name = "Old", lastUsedAtMillis = 1_000L)
        insert(name = "New", lastUsedAtMillis = 2_000L)

        val results = repo.search("   ", limit = 10).first()

        assertThat(results.map { it.name }).containsExactly("New", "Old").inOrder()
        assertThat(dao.searchCalls).isEqualTo(0)
    }

    @Test
    fun search_with_a_blank_query_lists_a_never_used_ingredient() = runTest {
        insert(name = "Used", lastUsedAtMillis = 1_000L)
        insert(name = "Haferflocken", lastUsedAtMillis = null)

        val results = repo.search("", limit = 10).first()

        assertThat(results.map { it.name }).containsExactly("Used", "Haferflocken").inOrder()
        assertThat(dao.searchCalls).isEqualTo(0)
    }

    @Test
    fun search_with_a_short_query_delegates_to_the_dao_like_search() = runTest {
        insert(name = "Oat milk")

        val results = repo.search("oa", limit = 10).first()

        assertThat(results.map { it.name }).containsExactly("Oat milk")
        assertThat(dao.searchCalls).isEqualTo(1)
        assertThat(dao.ftsCalls).isEqualTo(0)
    }

    /** P8.5: from three characters up the query goes through `ingredient_fts`, not `LIKE`. */
    @Test
    fun search_from_three_characters_uses_full_text_search() = runTest {
        insert(name = "Oat milk")

        val results = repo.search("oat", limit = 10).first()

        assertThat(results.map { it.name }).containsExactly("Oat milk")
        assertThat(dao.ftsCalls).isEqualTo(1)
        assertThat(dao.searchCalls).isEqualTo(0)
    }

    @Test
    fun the_fts_match_query_is_one_prefix_term_per_token() {
        assertThat(ftsMatchQuery("haf mil")).isEqualTo("haf* mil*")
        assertThat(ftsMatchQuery("  Oat-Milk ")).isEqualTo("Oat* Milk*")
        // FTS4 operators must never reach SQLite as operators.
        assertThat(ftsMatchQuery("oat*")).isEqualTo("oat*")
        assertThat(ftsMatchQuery("\"oat\" OR -milk")).isEqualTo("oat* OR* milk*")
        assertThat(ftsMatchQuery("***")).isNull()
        assertThat(ftsMatchQuery("")).isNull()
    }

    /** A punctuation-only query tokenises to nothing, so it must fall back to `LIKE`. */
    @Test
    fun a_query_with_no_tokens_falls_back_to_like() = runTest {
        insert(name = "Oat milk")

        repo.search("+++", limit = 10).first()

        assertThat(dao.searchCalls).isEqualTo(1)
        assertThat(dao.ftsCalls).isEqualTo(0)
    }

    @Test
    fun upsert_rejects_negative_kcal() = runTest {
        val result = repo.upsert(ingredient(kcal = -5.0))

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(dao.rows.value).isEmpty()
    }

    @Test
    fun upsert_rejects_a_blank_name() = runTest {
        val result = repo.upsert(ingredient(name = " "))

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun upsert_stores_a_valid_ingredient() = runTest {
        val result = repo.upsert(ingredient(name = "Rice"))

        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        assertThat(dao.rows.value.single().name).isEqualTo("Rice")
    }

    private suspend fun insert(name: String, lastUsedAtMillis: Long? = null): Long =
        (repo.upsert(ingredient(name = name, lastUsedAtMillis = lastUsedAtMillis)) as Outcome.Ok).value

    private fun ingredient(
        name: String = "Ingredient",
        kcal: Double = 100.0,
        lastUsedAtMillis: Long? = null,
    ) = Ingredient(
        id = 0L,
        name = name,
        brand = null,
        barcode = null,
        basis = MeasureBasis.PER_100G,
        pieceGrams = null,
        servingGrams = null,
        servingLabel = null,
        kcal = kcal,
        proteinG = 5.0,
        carbsG = 10.0,
        sugarG = null,
        fatG = 2.0,
        satFatG = null,
        fiberG = null,
        saltG = null,
        sodiumG = null,
        isFavorite = false,
        source = "MANUAL",
        offProductJson = null,
        lastUsedAtMillis = lastUsedAtMillis,
        useCount = 0,
        archived = false,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )
}

/** In-memory [IngredientDao] reproducing the ordering/filtering the real SQL queries do. */
private class FakeIngredientDao : IngredientDao {

    val rows = MutableStateFlow<List<com.myhealth.data.db.entity.IngredientEntity>>(emptyList())
    var searchCalls: Int = 0
    var ftsCalls: Int = 0
    private var nextId = 1L

    override suspend fun upsert(entity: com.myhealth.data.db.entity.IngredientEntity): Long {
        val id = if (entity.id == 0L) nextId++ else entity.id
        rows.value = rows.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override suspend fun getById(id: Long) = rows.value.firstOrNull { it.id == id }

    override suspend fun getByIds(ids: List<Long>) = rows.value.filter { it.id in ids }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override fun search(q: String, limit: Int) = rows.map { list ->
        searchCalls++
        list.filter { !it.archived && (it.name.contains(q, ignoreCase = true) || it.brand?.contains(q, ignoreCase = true) == true) }
            .sortedWith(compareByDescending<com.myhealth.data.db.entity.IngredientEntity> { it.isFavorite }.thenByDescending { it.useCount }.thenBy { it.name })
            .take(limit)
    }

    /** Mimics FTS prefix matching well enough for the routing tests: every `term*` must hit. */
    override fun searchFts(match: String, limit: Int) = rows.map { list ->
        ftsCalls++
        val terms = match.split(" ").map { it.removeSuffix("*") }.filter { it.isNotEmpty() }
        list.filter { row ->
            !row.archived && terms.all { term ->
                ("${row.name} ${row.brand.orEmpty()}").split(" ", "-")
                    .any { word -> word.startsWith(term, ignoreCase = true) }
            }
        }.take(limit)
    }

    override fun observeRecent(limit: Int) = rows.map { list ->
        list.filter { !it.archived && it.lastUsedAtMillis != null }
            .sortedByDescending { it.lastUsedAtMillis }
            .take(limit)
    }

    override fun observeAllActive(limit: Int) = rows.map { list ->
        list.filter { !it.archived }
            .sortedWith(
                compareBy<com.myhealth.data.db.entity.IngredientEntity> { it.lastUsedAtMillis == null }
                    .thenByDescending { it.lastUsedAtMillis ?: Long.MIN_VALUE }
                    .thenBy { it.name },
            )
            .take(limit)
    }

    override fun observeFavorites() = rows.map { list ->
        list.filter { !it.archived && it.isFavorite }.sortedBy { it.name }
    }

    override fun observeArchived(limit: Int) = rows.map { list ->
        list.filter { it.archived }.sortedBy { it.name }.take(limit)
    }

    override suspend fun getByBarcode(barcode: String) = rows.value.firstOrNull { it.barcode == barcode }

    override suspend fun markUsed(id: Long, atMillis: Long) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(useCount = it.useCount + 1, lastUsedAtMillis = atMillis, updatedAtMillis = atMillis) else it
        }
    }
}
