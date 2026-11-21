package com.myhealth.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * `user_exercise` (P26, DB v12) — an exercise the owner created, usually from the video import
 * screen. [id] starts with `USER_` and is random, so two devices merging backups never collide.
 *
 * Muscles are comma-separated `MuscleGroup` names and [equipment] / [pattern] are enum names; the
 * mapper decodes them leniently (an unknown name is dropped or falls back), so a row written by a
 * newer version still opens.
 */
@Serializable
@Entity(tableName = "user_exercise")
data class UserExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val primaryMuscles: String,
    val secondaryMuscles: String = "",
    val equipment: String,
    val pattern: String,
    val isTimed: Boolean = false,
    val unilateral: Boolean = false,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "0")
    val archived: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
