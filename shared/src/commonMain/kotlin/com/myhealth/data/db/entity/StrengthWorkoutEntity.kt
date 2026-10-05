package com.myhealth.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.StrengthWorkoutKind
import kotlinx.serialization.Serializable

/**
 * `strength_workout` (PLAN §2.2.7, P14) — a named, ordered list of exercises.
 *
 * [templateId] names a built-in of `StrengthTemplates` and is **unique where not null**, which is
 * what makes `StrengthWorkoutSeeder` idempotent (P14.4): seeding twice cannot produce two
 * `UPPER_A` rows. A user's copy of a built-in keeps [isBuiltIn] `false` and no template id, so it
 * is never overwritten when a template is corrected in code.
 *
 * `estimatedMinutes` is computed from the rows (§3.12.3), never stored.
 */
@Serializable
@Entity(
    tableName = "strength_workout",
    indices = [Index(value = ["templateId"], unique = true, name = "uq_strength_workout_template")],
)
data class StrengthWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val kind: StrengthWorkoutKind,
    val templateId: String? = null,
    val isBuiltIn: Boolean = false,
    val notes: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** P19 (DB v8): the "Use in suggestions" checkbox; every pre-0.8.0 workout starts checked. */
    @ColumnInfo(defaultValue = "1")
    val useInSuggestions: Boolean = true,
)
