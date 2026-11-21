package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * `exercise_alias` (P26, DB v12) — a name the owner matched to an exercise by hand on the video
 * import screen, normalised (`ExerciseMatcher.normalise`). Not a foreign key: [exerciseId] is a
 * catalog id or a `user_exercise` id.
 */
@Serializable
@Entity(tableName = "exercise_alias")
data class ExerciseAliasEntity(
    @PrimaryKey val normalisedName: String,
    val exerciseId: String,
    val createdAtMillis: Long,
)
