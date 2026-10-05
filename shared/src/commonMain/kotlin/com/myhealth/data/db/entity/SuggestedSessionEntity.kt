package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.SuggestionStatus
import kotlinx.serialization.Serializable

/**
 * `suggested_session` (PLAN §2.2.4) — output of the suggestion engine, persisted so the review
 * screen survives process death. Owned by its batch (`CASCADE`).
 */
@Serializable
@Entity(
    tableName = "suggested_session",
    foreignKeys = [
        ForeignKey(
            entity = SuggestionBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["batchId"], name = "idx_suggested_batch"),
        Index(value = ["day"], name = "idx_suggested_day"),
    ],
)
data class SuggestedSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val batchId: Long,
    val day: Long,
    val sportType: SportType,
    val sessionType: SessionType,
    val intensity: Intensity,
    val targetDurationMin: Int? = null,
    val targetDistanceMeters: Double? = null,
    val estimatedTrimp: Double,
    val score: Double,
    /** JSON list of `{ruleId, text}` (§3.5.6 step 8). */
    val rationaleJson: String,
    val status: SuggestionStatus = SuggestionStatus.PROPOSED,
    /** The recommended pace for this session type (P14, DB v6; §3.10). */
    val targetPaceSecPerKm: Int? = null,
    /** The `WorkoutStructure` of §3.11 as JSON (P14, DB v6). */
    val structureJson: String? = null,
    /** A built-in `StrengthTemplates` id, materialised into a workout row on accept (P14, DB v6). */
    val workoutTemplateId: String? = null,
    /** P19 (DB v8): a concrete `strength_workout` id from the checked pool; no FK (a proposal). */
    val workoutId: Long? = null,
)
