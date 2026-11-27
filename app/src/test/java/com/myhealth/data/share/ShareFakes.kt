package com.myhealth.data.share

import com.myhealth.data.db.dao.ShareDao
import com.myhealth.data.db.entity.ShareConfigEntity
import com.myhealth.data.db.entity.ShareLinkEntity
import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.ExerciseAlias
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MealLog
import com.myhealth.domain.model.MealLogSummary
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.model.StrengthSetLog
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.UserExercise
import com.myhealth.domain.repository.IngredientRepository
import com.myhealth.domain.repository.MealItemInput
import com.myhealth.domain.repository.MealRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.repository.UserExerciseRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory stand-ins for one device's content, for the P27 share tests. */
internal class FakeShareDao : ShareDao {
    val config = MutableStateFlow<ShareConfigEntity?>(null)
    val links = MutableStateFlow<List<ShareLinkEntity>>(emptyList())

    override fun observeConfig(): Flow<ShareConfigEntity?> = config
    override suspend fun getConfig(): ShareConfigEntity? = config.value
    override suspend fun upsertConfig(row: ShareConfigEntity) { config.value = row }
    override suspend fun deleteConfig() { config.value = null }
    override fun observeLinks(): Flow<List<ShareLinkEntity>> = links
    override suspend fun getLinks(): List<ShareLinkEntity> = links.value
    override suspend fun getLink(kind: String, localKey: String) = links.value.firstOrNull { it.kind == kind && it.localKey == localKey }
    override suspend fun getLinkByShareId(shareId: String) = links.value.firstOrNull { it.shareId == shareId }
    override suspend fun replaceLink(row: ShareLinkEntity) {
        links.value = links.value.filterNot { it.kind == row.kind && it.localKey == row.localKey } + row
    }
    override suspend fun upsertLink(row: ShareLinkEntity) {
        val clash = links.value.any { it.shareId != null && it.shareId == row.shareId && (it.kind != row.kind || it.localKey != row.localKey) }
        check(!clash) { "UNIQUE constraint failed: share_link.shareId" }
        links.value = links.value.filterNot { it.kind == row.kind && it.localKey == row.localKey } + row
    }
    override suspend fun deleteLink(kind: String, localKey: String) {
        links.value = links.value.filterNot { it.kind == kind && it.localKey == localKey }
    }
}

internal class FakeUserExercises : UserExerciseRepository {
    val rows = MutableStateFlow<Map<String, UserExercise>>(emptyMap())
    override fun observeLibrary(): Flow<ExerciseLibrary> = rows.map { ExerciseLibrary(it.values.toList()) }
    override suspend fun getLibrary() = ExerciseLibrary(rows.value.values.toList())
    override suspend fun upsert(exercise: UserExercise): Outcome<Unit> {
        if (exercise.exercise.primary.isEmpty()) return Outcome.Err(AppError.Validation("primary", "required"))
        rows.value = rows.value + (exercise.exercise.id to exercise)
        return Outcome.Ok(Unit)
    }
    override suspend fun usageCount(id: String) = 0
    override suspend fun setArchived(id: String, archived: Boolean): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun delete(id: String): Outcome<Unit> { rows.value = rows.value - id; return Outcome.Ok(Unit) }
    override fun observeAliases(): Flow<List<ExerciseAlias>> = MutableStateFlow(emptyList())
    override suspend fun getAliases(): List<ExerciseAlias> = emptyList()
    override suspend fun putAlias(alias: ExerciseAlias): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun deleteAlias(normalisedName: String): Outcome<Unit> = Outcome.Ok(Unit)
}

internal class FakeIngredients : IngredientRepository {
    val rows = linkedMapOf<Long, Ingredient>()
    private var next = 100L
    override fun search(query: String, limit: Int): Flow<List<Ingredient>> = MutableStateFlow(rows.values.toList())
    override fun observeRecent(limit: Int): Flow<List<Ingredient>> = MutableStateFlow(emptyList())
    override fun observeFavorites(): Flow<List<Ingredient>> = MutableStateFlow(emptyList())
    override fun observeArchived(limit: Int): Flow<List<Ingredient>> = MutableStateFlow(emptyList())
    override suspend fun getById(id: Long) = rows[id]
    override suspend fun getByIds(ids: List<Long>) = ids.mapNotNull { rows[it] }
    override suspend fun getByBarcode(barcode: String) = rows.values.firstOrNull { it.barcode == barcode }
    override suspend fun upsert(ingredient: Ingredient): Outcome<Long> {
        val id = if (ingredient.id == 0L) next++ else ingredient.id
        if (ingredient.barcode != null && rows.values.any { it.barcode == ingredient.barcode && it.id != id }) {
            return Outcome.Err(AppError.Storage(IllegalStateException("UNIQUE barcode")))
        }
        rows[id] = ingredient.copy(id = id)
        return Outcome.Ok(id)
    }
    override suspend fun markUsed(id: Long): Outcome<Unit> {
        rows[id]?.let { rows[id] = it.copy(useCount = it.useCount + 1, updatedAtMillis = it.updatedAtMillis + 1) }
        return Outcome.Ok(Unit)
    }
    override suspend fun setFavorite(id: Long, favorite: Boolean): Outcome<Unit> {
        rows[id]?.let { rows[id] = it.copy(isFavorite = favorite) }
        return Outcome.Ok(Unit)
    }
    override suspend fun deleteOrArchive(id: Long): Outcome<Unit> { rows.remove(id); return Outcome.Ok(Unit) }
}

internal class FakeMeals : MealRepository {
    val templates = linkedMapOf<Long, MealTemplate>()
    private var next = 200L
    override fun observeDay(day: Long): Flow<List<MealLog>> = TODO()
    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<MealLogSummary>> = TODO()
    override suspend fun getLog(id: Long): MealLog? = TODO()
    override suspend fun logMeal(day: Long, slot: MealSlot, items: List<MealItemInput>, name: String?, atMinuteOfDay: Int?, templateId: Long?): Outcome<Long> = TODO()
    override suspend fun updateItemQuantity(itemId: Long, quantity: Double, unit: QuantityUnit): Outcome<Unit> = TODO()
    override suspend fun deleteItem(itemId: Long): Outcome<Unit> = TODO()
    override suspend fun deleteLog(id: Long): Outcome<Unit> = TODO()
    override suspend fun copyDay(fromDay: Long, toDay: Long): Outcome<Unit> = TODO()
    override fun observeTemplates(): Flow<List<MealTemplate>> = MutableStateFlow(templates.values.toList())
    override suspend fun getTemplate(id: Long) = templates[id]
    override suspend fun upsertTemplate(template: MealTemplate): Outcome<Long> {
        val id = if (template.id == 0L) next++ else template.id
        templates[id] = template.copy(id = id, items = template.items.map { it.copy(templateId = id) })
        return Outcome.Ok(id)
    }
    override suspend fun deleteTemplate(id: Long): Outcome<Unit> { templates.remove(id); return Outcome.Ok(Unit) }
    override suspend fun logTemplate(templateId: Long, day: Long, slot: MealSlot, servings: Double): Outcome<Long> = TODO()
}

internal class FakeWorkouts : StrengthRepository {
    val rows = linkedMapOf<Long, StrengthWorkout>()
    private var next = 300L
    override fun observeAll(): Flow<List<StrengthWorkout>> = MutableStateFlow(rows.values.toList())
    override fun observe(id: Long): Flow<StrengthWorkout?> = MutableStateFlow(rows[id])
    override suspend fun getById(id: Long) = rows[id]
    override suspend fun getByTemplateId(templateId: String) = rows.values.firstOrNull { it.templateId == templateId }
    override suspend fun upsertWorkout(workout: StrengthWorkout): Outcome<Long> {
        val id = if (workout.id == 0L) next++ else workout.id
        rows[id] = workout.copy(id = id, exercises = workout.exercises.mapIndexed { i, e -> e.copy(workoutId = id, orderIndex = i) })
        return Outcome.Ok(id)
    }
    override suspend fun deleteWorkout(id: Long): Outcome<Unit> { rows.remove(id); return Outcome.Ok(Unit) }
    override suspend fun insertSetLogs(logs: List<StrengthSetLog>): Outcome<Unit> = TODO()
    override suspend fun saveSetLogs(logs: List<StrengthSetLog>): Outcome<Map<String, ExerciseProgress>> = TODO()
    override fun observeSetLogsByDay(day: Long): Flow<List<StrengthSetLog>> = TODO()
    override suspend fun getSetLogsOfPlannedSession(plannedSessionId: Long): List<StrengthSetLog> = TODO()
    override suspend fun getRecentSetLogs(exerciseId: String, limit: Int): List<StrengthSetLog> = TODO()
    override suspend fun deleteSetLog(id: Long): Outcome<Unit> = TODO()
    override fun observeProgress(exerciseId: String): Flow<ExerciseProgress?> = TODO()
    override suspend fun getProgress(exerciseId: String): ExerciseProgress? = TODO()
    override suspend fun getAllProgress(): List<ExerciseProgress> = TODO()
    override suspend fun upsertProgress(progress: ExerciseProgress): Outcome<Unit> = TODO()
    override suspend fun prescriptionFor(exercise: Exercise, bodyWeightKg: Double): ExercisePrescription = TODO()
}

/** One device: its content plus its [ShareService]. */
internal class Device(endpoints: FirebaseEndpoints) {
    val dao = FakeShareDao()
    val exercises = FakeUserExercises()
    val ingredients = FakeIngredients()
    val meals = FakeMeals()
    val workouts = FakeWorkouts()
    val service = ShareService(
        dao, exercises, ingredients, meals, workouts, java.time.Clock.systemUTC(),
        clientFor = { p, k -> FirestoreClient(p, k, endpoints, { System.currentTimeMillis() }) },
    )
}
