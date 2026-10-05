package com.myhealth.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.myhealth.data.db.entity.StrengthWorkoutEntity
import com.myhealth.data.db.entity.StrengthWorkoutExerciseEntity

/**
 * `strength_workout` joined with its rows (PLAN §2.2.7) — the shape every strength screen reads,
 * since a workout without its exercises is not a workout.
 *
 * Room does not promise an order for a `@Relation`, so the mapper sorts by `orderIndex`
 * (`sw06`); `uq_swe_order` guarantees that sort is total.
 */
data class StrengthWorkoutWithExercises(
    @Embedded val workout: StrengthWorkoutEntity,
    @Relation(parentColumn = "id", entityColumn = "workoutId")
    val exercises: List<StrengthWorkoutExerciseEntity> = emptyList(),
)
