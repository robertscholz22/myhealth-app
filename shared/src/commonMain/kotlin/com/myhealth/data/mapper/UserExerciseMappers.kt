package com.myhealth.data.mapper

import com.myhealth.data.db.entity.ExerciseAliasEntity
import com.myhealth.data.db.entity.UserExerciseEntity
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.ExerciseAlias
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.UserExercise

/**
 * `user_exercise` / `exercise_alias` ⇄ domain (P26). Decoding is lenient: an unknown muscle name is
 * dropped, an unknown equipment falls back to bodyweight and an unknown pattern to `OTHER`, so a
 * row from a newer version (or a hand-edited backup) still opens.
 */
fun UserExerciseEntity.toDomain(): UserExercise {
    val primary = decodeMuscles(primaryMuscles)
    return UserExercise(
        exercise = Exercise(
            id = id,
            name = name,
            primary = primary,
            secondary = decodeMuscles(secondaryMuscles) - primary,
            equipment = Equipment.entries.firstOrNull { it.name == equipment } ?: Equipment.BODYWEIGHT,
            pattern = MovementPattern.entries.firstOrNull { it.name == pattern } ?: MovementPattern.OTHER,
            unilateral = unilateral,
            isTimed = isTimed,
            cue = notes.orEmpty(),
        ),
        notes = notes,
        archived = archived,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}

fun UserExercise.toEntity(): UserExerciseEntity = UserExerciseEntity(
    id = exercise.id,
    name = exercise.name.trim(),
    primaryMuscles = encodeMuscles(exercise.primary),
    secondaryMuscles = encodeMuscles(exercise.secondary - exercise.primary),
    equipment = exercise.equipment.name,
    pattern = exercise.pattern.name,
    isTimed = exercise.isTimed,
    unilateral = exercise.unilateral,
    notes = notes?.trim()?.takeIf { it.isNotEmpty() },
    archived = archived,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun ExerciseAliasEntity.toDomain(): ExerciseAlias = ExerciseAlias(normalisedName, exerciseId, createdAtMillis)

fun ExerciseAlias.toEntity(): ExerciseAliasEntity = ExerciseAliasEntity(normalisedName, exerciseId, createdAtMillis)

/** Enum order, so the stored text does not depend on set iteration order. */
internal fun encodeMuscles(muscles: Set<MuscleGroup>): String =
    MuscleGroup.entries.filter { it in muscles }.joinToString(",") { it.name }

internal fun decodeMuscles(text: String): Set<MuscleGroup> =
    text.split(',').mapNotNull { token -> MuscleGroup.entries.firstOrNull { it.name == token.trim() } }.toSet()
