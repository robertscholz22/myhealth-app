package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.myhealth.data.db.entity.ExerciseProgressEntity
import com.myhealth.data.db.entity.StrengthSetLogEntity
import com.myhealth.data.db.entity.StrengthWorkoutEntity
import com.myhealth.data.db.entity.StrengthWorkoutExerciseEntity
import com.myhealth.data.db.relation.StrengthWorkoutWithExercises
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the three strength tables (PLAN §2.2.7, P14).
 *
 * A workout is only ever read together with its rows, so every read returns
 * [StrengthWorkoutWithExercises] and carries `@Transaction` (a `@Relation` runs a second query;
 * without the annotation the two could see different states of the database).
 *
 * Writing the rows is "delete then insert", not a diff: the editor hands over the whole ordered
 * list, `uq_swe_order` forbids a transient duplicate position, and a workout has a handful of rows
 * — so rewriting them is both simpler and cheaper than reconciling ids.
 */
@Dao
interface StrengthDao {

    @Transaction
    @Query("SELECT * FROM strength_workout ORDER BY name ASC")
    fun observeAll(): Flow<List<StrengthWorkoutWithExercises>>

    @Transaction
    @Query("SELECT * FROM strength_workout WHERE id = :id")
    fun observeById(id: Long): Flow<StrengthWorkoutWithExercises?>

    @Transaction
    @Query("SELECT * FROM strength_workout WHERE id = :id")
    suspend fun getById(id: Long): StrengthWorkoutWithExercises?

    /** The seeded row of a built-in, if it has been materialised (`StrengthWorkoutSeeder`). */
    @Transaction
    @Query("SELECT * FROM strength_workout WHERE templateId = :templateId")
    suspend fun getByTemplateId(templateId: String): StrengthWorkoutWithExercises?

    @Upsert
    suspend fun upsertWorkout(workout: StrengthWorkoutEntity): Long

    @Upsert
    suspend fun upsertExercises(rows: List<StrengthWorkoutExerciseEntity>)

    @Query("DELETE FROM strength_workout_exercise WHERE workoutId = :workoutId")
    suspend fun deleteExercisesOf(workoutId: Long)

    @Query("DELETE FROM strength_workout WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

    @Query("UPDATE strength_workout SET useInSuggestions = :use, updatedAtMillis = :nowMillis WHERE id = :id")
    suspend fun setUseInSuggestions(id: Long, use: Boolean, nowMillis: Long)

    @Insert
    suspend fun insertSetLogs(rows: List<StrengthSetLogEntity>): List<Long>

    @Query(
        "SELECT * FROM strength_set_log WHERE day = :day " +
            "ORDER BY exerciseId ASC, setIndex ASC, completedAtMillis ASC",
    )
    fun observeSetLogsByDay(day: Long): Flow<List<StrengthSetLogEntity>>

    /** Every set logged against one planned session — the "what did I lift last time" lookup. */
    @Query("SELECT * FROM strength_set_log WHERE plannedSessionId = :plannedSessionId ORDER BY setIndex ASC")
    suspend fun getSetLogsOfPlannedSession(plannedSessionId: Long): List<StrengthSetLogEntity>

    @Query("DELETE FROM strength_set_log WHERE id = :id")
    suspend fun deleteSetLog(id: Long)

    // ---- exercise_progress (P16.1) -----------------------------------------------------------

    @Query("SELECT * FROM exercise_progress WHERE exerciseId = :exerciseId")
    fun observeProgress(exerciseId: String): Flow<ExerciseProgressEntity?>

    @Query("SELECT * FROM exercise_progress WHERE exerciseId = :exerciseId")
    suspend fun getProgress(exerciseId: String): ExerciseProgressEntity?

    @Query("SELECT * FROM exercise_progress ORDER BY exerciseId ASC")
    suspend fun getAllProgress(): List<ExerciseProgressEntity>

    /** The catalog id is the primary key, so this replaces the exercise's state in place. */
    @Upsert
    suspend fun upsertProgress(row: ExerciseProgressEntity)

    @Query("DELETE FROM exercise_progress WHERE exerciseId = :exerciseId")
    suspend fun deleteProgress(exerciseId: String)

    /** The last sessions logged for one exercise — what the Progression card lists (P16.2). */
    @Query(
        "SELECT * FROM strength_set_log WHERE exerciseId = :exerciseId " +
            "ORDER BY day DESC, setIndex ASC LIMIT :limit",
    )
    suspend fun getRecentSetLogs(exerciseId: String, limit: Int): List<StrengthSetLogEntity>
}
