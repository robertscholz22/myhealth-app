package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.ExerciseAliasEntity
import com.myhealth.data.db.entity.UserExerciseEntity
import kotlinx.coroutines.flow.Flow

/** P26: the owner's exercises and the names learned on the video import screen. */
@Dao
interface UserExerciseDao {

    @Query("SELECT * FROM user_exercise ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<UserExerciseEntity>>

    @Query("SELECT * FROM user_exercise ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<UserExerciseEntity>

    @Query("SELECT * FROM user_exercise WHERE id = :id")
    suspend fun getById(id: String): UserExerciseEntity?

    @Upsert
    suspend fun upsert(row: UserExerciseEntity)

    @Query("DELETE FROM user_exercise WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM strength_workout_exercise WHERE exerciseId = :id")
    suspend fun countWorkoutRows(id: String): Int

    @Query("SELECT * FROM exercise_alias ORDER BY normalisedName ASC")
    fun observeAliases(): Flow<List<ExerciseAliasEntity>>

    @Query("SELECT * FROM exercise_alias ORDER BY normalisedName ASC")
    suspend fun getAliases(): List<ExerciseAliasEntity>

    @Upsert
    suspend fun upsertAlias(row: ExerciseAliasEntity)

    @Query("DELETE FROM exercise_alias WHERE normalisedName = :normalisedName")
    suspend fun deleteAlias(normalisedName: String)

    @Query("DELETE FROM exercise_alias WHERE exerciseId = :exerciseId")
    suspend fun deleteAliasesOf(exerciseId: String)
}
