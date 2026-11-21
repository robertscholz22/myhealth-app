package com.myhealth.data.repository

import com.myhealth.data.db.dao.UserExerciseDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.engine.strength.ExerciseRegistry
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.ExerciseAlias
import com.myhealth.domain.model.UserExercise
import com.myhealth.domain.repository.UserExerciseRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.systemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Room-backed [UserExerciseRepository] (P26). Every read of the table installs the resulting
 * [ExerciseLibrary] in [ExerciseRegistry]; writes re-read and install before they return, so the
 * caller's next lookup by id already finds what it just saved.
 */
class RoomUserExerciseRepository(
    private val dao: UserExerciseDao,
    private val clock: PlatformClock = systemClock(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserExerciseRepository {

    @kotlin.concurrent.Volatile
    private var loaded: ExerciseLibrary? = null

    override fun observeLibrary(): Flow<ExerciseLibrary> =
        dao.observeAll()
            .map { rows -> ExerciseLibrary(rows.map { it.toDomain() }) }
            .onEach { install(it) }

    override suspend fun getLibrary(): ExerciseLibrary = loaded ?: reload()

    override suspend fun upsert(exercise: UserExercise): Outcome<Unit> = withContext(ioDispatcher) {
        val e = exercise.exercise
        when {
            !e.id.startsWith(Exercise.USER_EXERCISE_PREFIX) ->
                Outcome.Err(AppError.Validation("id", "not a user exercise id"))
            e.name.isBlank() -> Outcome.Err(AppError.Validation("name", "required"))
            e.primary.isEmpty() -> Outcome.Err(AppError.Validation("primary", "required"))
            else -> runCatchingApp {
                val existing = dao.getById(e.id)
                val now = clock.millis()
                dao.upsert(
                    exercise.copy(
                        createdAtMillis = existing?.createdAtMillis ?: now,
                        updatedAtMillis = now,
                    ).toEntity(),
                )
            }.also { reload() }
        }
    }

    override suspend fun usageCount(id: String): Int = withContext(ioDispatcher) { dao.countWorkoutRows(id) }

    override suspend fun setArchived(id: String, archived: Boolean): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp {
            val row = dao.getById(id) ?: return@runCatchingApp
            dao.upsert(row.copy(archived = archived, updatedAtMillis = clock.millis()))
        }.also { reload() }
    }

    override suspend fun delete(id: String): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp {
            dao.deleteAliasesOf(id)
            dao.delete(id)
        }.also { reload() }
    }

    override fun observeAliases(): Flow<List<ExerciseAlias>> =
        dao.observeAliases().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAliases(): List<ExerciseAlias> =
        withContext(ioDispatcher) { dao.getAliases().map { it.toDomain() } }

    override suspend fun putAlias(alias: ExerciseAlias): Outcome<Unit> = withContext(ioDispatcher) {
        if (alias.normalisedName.isBlank()) return@withContext Outcome.Ok(Unit)
        runCatchingApp { dao.upsertAlias(alias.toEntity()) }
    }

    override suspend fun deleteAlias(normalisedName: String): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { dao.deleteAlias(normalisedName) }
    }

    private suspend fun reload(): ExerciseLibrary = withContext(ioDispatcher) {
        ExerciseLibrary(dao.getAll().map { it.toDomain() }).also { install(it) }
    }

    private fun install(library: ExerciseLibrary) {
        loaded = library
        ExerciseRegistry.install(library)
    }
}
