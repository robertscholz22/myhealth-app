package com.myhealth.data.mapper

import com.myhealth.data.db.entity.ExerciseProgressEntity
import com.myhealth.data.db.entity.StrengthSetLogEntity
import com.myhealth.data.db.entity.StrengthWorkoutEntity
import com.myhealth.data.db.entity.StrengthWorkoutExerciseEntity
import com.myhealth.data.db.relation.StrengthWorkoutWithExercises
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.StrengthSetLog
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise

/**
 * `strength_workout`, `strength_workout_exercise` and `strength_set_log` ⇄ their domain models
 * (PLAN §2.2.7 / §3.12.3, P14.1).
 *
 * The only non-trivial part is the order: Room's `@Relation` makes no promise about it, so
 * [StrengthWorkoutWithExercises.toDomain] sorts by `orderIndex` — the domain model documents that
 * its list is ordered, and every screen relies on it (`sw06`).
 */

fun StrengthWorkoutWithExercises.toDomain(): StrengthWorkout = StrengthWorkout(
    id = workout.id,
    name = workout.name,
    kind = workout.kind,
    templateId = workout.templateId,
    isBuiltIn = workout.isBuiltIn,
    notes = workout.notes,
    exercises = exercises.sortedBy { it.orderIndex }.map { it.toDomain() },
    createdAtMillis = workout.createdAtMillis,
    updatedAtMillis = workout.updatedAtMillis,
    useInSuggestions = workout.useInSuggestions,
)

/** The header row only; the children are written separately (see `RoomStrengthRepository`). */
fun StrengthWorkout.toEntity(): StrengthWorkoutEntity = StrengthWorkoutEntity(
    id = id,
    name = name,
    kind = kind,
    templateId = templateId,
    isBuiltIn = isBuiltIn,
    notes = notes,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    useInSuggestions = useInSuggestions,
)

fun StrengthWorkoutExerciseEntity.toDomain(): StrengthWorkoutExercise = StrengthWorkoutExercise(
    id = id,
    workoutId = workoutId,
    orderIndex = orderIndex,
    exerciseId = exerciseId,
    sets = sets,
    reps = reps,
    seconds = seconds,
    loadKg = loadKg,
    isBodyweight = isBodyweight,
    restSec = restSec,
    note = note,
)

/**
 * [workoutId] overrides the row's own parent id, so a freshly inserted workout can adopt rows that
 * were built before its id existed (the editor works on a draft with `workoutId = 0`).
 */
fun StrengthWorkoutExercise.toEntity(workoutId: Long = this.workoutId): StrengthWorkoutExerciseEntity =
    StrengthWorkoutExerciseEntity(
        id = id,
        workoutId = workoutId,
        orderIndex = orderIndex,
        exerciseId = exerciseId,
        sets = sets,
        reps = reps,
        seconds = seconds,
        loadKg = loadKg,
        isBodyweight = isBodyweight,
        restSec = restSec,
        note = note,
    )

fun StrengthSetLogEntity.toDomain(): StrengthSetLog = StrengthSetLog(
    id = id,
    day = day,
    plannedSessionId = plannedSessionId,
    activityId = activityId,
    exerciseId = exerciseId,
    setIndex = setIndex,
    reps = reps,
    seconds = seconds,
    loadKg = loadKg,
    rpe = rpe,
    completedAtMillis = completedAtMillis,
    feedback = feedback,
)

fun StrengthSetLog.toEntity(): StrengthSetLogEntity = StrengthSetLogEntity(
    id = id,
    day = day,
    plannedSessionId = plannedSessionId,
    activityId = activityId,
    exerciseId = exerciseId,
    setIndex = setIndex,
    reps = reps,
    seconds = seconds,
    loadKg = loadKg,
    rpe = rpe,
    completedAtMillis = completedAtMillis,
    feedback = feedback,
)

/**
 * `exercise_progress` ⇄ [ExerciseProgress] (§P16, P16.1) — a 1:1 mapping keyed on the catalog id,
 * which is the table's primary key, so an upsert of the same exercise always replaces its state.
 */
fun ExerciseProgressEntity.toDomain(): ExerciseProgress = ExerciseProgress(
    exerciseId = exerciseId,
    loadKg = loadKg,
    reps = reps,
    seconds = seconds,
    lastFeedback = lastFeedback,
    isEstimated = isEstimated,
    updatedDay = updatedDay,
)

fun ExerciseProgress.toEntity(): ExerciseProgressEntity = ExerciseProgressEntity(
    exerciseId = exerciseId,
    loadKg = loadKg,
    reps = reps,
    seconds = seconds,
    lastFeedback = lastFeedback,
    isEstimated = isEstimated,
    updatedDay = updatedDay,
)
