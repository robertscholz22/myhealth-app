package com.myhealth.domain.repository

import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.model.ExerciseAlias
import com.myhealth.domain.model.UserExercise
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * P26: the owner's own exercises (`user_exercise`) and the names learned on the video import
 * screen (`exercise_alias`).
 *
 * Every change also installs a fresh [ExerciseLibrary] in `ExerciseRegistry`, so lookups by id
 * anywhere in the app see a new exercise as soon as it is saved.
 */
interface UserExerciseRepository {

    /** The catalog plus the owner's exercises, re-emitted on every change. */
    fun observeLibrary(): Flow<ExerciseLibrary>

    /** Loads the table once and installs it in `ExerciseRegistry` (cheap after the first call). */
    suspend fun getLibrary(): ExerciseLibrary

    /** Inserts or replaces; a new exercise needs an id from `UserExerciseIds.next`. */
    suspend fun upsert(exercise: UserExercise): Outcome<Unit>

    /** How many workout rows use the exercise — deleting is only offered when it is 0. */
    suspend fun usageCount(id: String): Int

    suspend fun setArchived(id: String, archived: Boolean): Outcome<Unit>

    /** Deletes the exercise and its learned names; the caller checks [usageCount] first. */
    suspend fun delete(id: String): Outcome<Unit>

    fun observeAliases(): Flow<List<ExerciseAlias>>

    suspend fun getAliases(): List<ExerciseAlias>

    suspend fun putAlias(alias: ExerciseAlias): Outcome<Unit>

    suspend fun deleteAlias(normalisedName: String): Outcome<Unit>
}
