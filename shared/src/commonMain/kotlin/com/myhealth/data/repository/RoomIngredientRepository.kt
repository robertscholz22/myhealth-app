package com.myhealth.data.repository

import com.myhealth.data.db.dao.IngredientDao
import com.myhealth.data.db.dao.MealDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.repository.IngredientRepository
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
 * Room-backed [IngredientRepository] (PLAN §2.2.5, P4.2) over [IngredientDao]. [mealDao] is only
 * used to answer "is this ingredient still referenced?" for [deleteOrArchive] — the archive vs.
 * hard-delete branch keeps a logged/templated ingredient's history readable (§2.2.5 rationale on
 * `meal_log_item`).
 */
class RoomIngredientRepository(
    private val ingredientDao: IngredientDao,
    private val mealDao: MealDao,
    private val clock: PlatformClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IngredientRepository {

    override fun search(query: String, limit: Int): Flow<List<Ingredient>> {
        val trimmed = query.trim()
        val match = ftsMatchQuery(trimmed)
        return when {
            // BUG-2: an empty query lists every active ingredient, not only the used ones, so a
            // freshly created ingredient shows up in the Ingredients list and in both pickers.
            trimmed.isEmpty() -> ingredientDao.observeAllActive(limit)
            // P8.5: full text from three characters up, `LIKE` below that — a one- or two-letter
            // prefix matches so much of the index that the scan is the cheaper of the two, and a
            // query made only of punctuation tokenises to nothing at all.
            trimmed.length >= FTS_MIN_QUERY_LENGTH && match != null -> ingredientDao.searchFts(match, limit)
            else -> ingredientDao.search(trimmed, limit)
        }.map { rows -> rows.map { it.toDomain() } }
    }

    override fun observeRecent(limit: Int): Flow<List<Ingredient>> =
        ingredientDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeFavorites(): Flow<List<Ingredient>> =
        ingredientDao.observeFavorites().map { rows -> rows.map { it.toDomain() } }

    override fun observeArchived(limit: Int): Flow<List<Ingredient>> =
        ingredientDao.observeArchived(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getById(id: Long): Ingredient? =
        withContext(ioDispatcher) { ingredientDao.getById(id)?.toDomain() }

    override suspend fun getByIds(ids: List<Long>): List<Ingredient> =
        withContext(ioDispatcher) {
            if (ids.isEmpty()) emptyList() else ingredientDao.getByIds(ids).map { it.toDomain() }
        }

    override suspend fun getByBarcode(barcode: String): Ingredient? =
        withContext(ioDispatcher) { ingredientDao.getByBarcode(barcode)?.toDomain() }

    override suspend fun upsert(ingredient: Ingredient): Outcome<Long> = withContext(ioDispatcher) {
        val error = ingredient.requiredFieldError()
        if (error != null) return@withContext Outcome.Err(error)
        runCatchingApp {
            val now = clock.millis()
            val entity = ingredient.toEntity().let {
                it.copy(updatedAtMillis = now, createdAtMillis = if (it.id == 0L) now else it.createdAtMillis)
            }
            ingredientDao.upsert(entity)
        }
    }

    override suspend fun markUsed(id: Long): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { ingredientDao.markUsed(id, clock.millis()) }
    }

    override suspend fun setFavorite(id: Long, favorite: Boolean): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp {
            val entity = ingredientDao.getById(id) ?: return@runCatchingApp
            ingredientDao.upsert(entity.copy(isFavorite = favorite, updatedAtMillis = clock.millis()))
        }
    }

    override suspend fun deleteOrArchive(id: Long): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp {
            val referenced = mealDao.countLogItemsByIngredient(id) > 0 ||
                mealDao.countTemplateItemsByIngredient(id) > 0
            if (referenced) {
                val entity = ingredientDao.getById(id) ?: return@runCatchingApp
                ingredientDao.upsert(entity.copy(archived = true, updatedAtMillis = clock.millis()))
            } else {
                ingredientDao.deleteById(id)
            }
        }
    }

    /** kcal, protein, carbs and fat are "effectively required" (§2.2.5 comment on `ingredient`):
     * present and non-negative. The rest of the nutrient columns stay optional. */
    private fun Ingredient.requiredFieldError(): AppError.Validation? = when {
        name.isBlank() -> AppError.Validation("name", "Name is required.")
        kcal < 0.0 -> AppError.Validation("kcal", "Calories cannot be negative.")
        proteinG == null -> AppError.Validation("proteinG", "Protein is required.")
        proteinG!! < 0.0 -> AppError.Validation("proteinG", "Protein cannot be negative.")
        carbsG == null -> AppError.Validation("carbsG", "Carbohydrate is required.")
        carbsG!! < 0.0 -> AppError.Validation("carbsG", "Carbohydrate cannot be negative.")
        fatG == null -> AppError.Validation("fatG", "Fat is required.")
        fatG!! < 0.0 -> AppError.Validation("fatG", "Fat cannot be negative.")
        else -> null
    }
}

/** Queries shorter than this use `LIKE`; from here up they use `ingredient_fts` (P8.5). */
const val FTS_MIN_QUERY_LENGTH: Int = 3

/**
 * A user query turned into an FTS4 MATCH expression: one prefix term per token, so "haf mil"
 * becomes `haf* mil*` and matches "Hafermilch". Everything that is not a letter or a digit is a
 * separator — FTS4's own operators (`*`, `"`, `-`, `OR`, `NEAR`) would otherwise make a stray
 * character a syntax error and crash the query. `null` when nothing tokenisable is left.
 *
 * Pure — unit-tested in `RoomIngredientRepositoryTest`.
 */
fun ftsMatchQuery(query: String): String? {
    val terms = query
        .split(*FTS_SEPARATORS)
        .map { token -> token.filter { it.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
    return if (terms.isEmpty()) null else terms.joinToString(" ") { "$it*" }
}

private val FTS_SEPARATORS: Array<String> = arrayOf(" ", "\t", "\n", ",", ";", "/", "-", "+", "(", ")")
