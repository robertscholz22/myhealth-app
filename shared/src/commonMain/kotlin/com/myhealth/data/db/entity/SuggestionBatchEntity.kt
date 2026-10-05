package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.TrainingPhase
import kotlinx.serialization.Serializable

/**
 * `suggestion_batch` (PLAN §2.2.4) — one run of the suggestion engine over a horizon.
 * [inputsHash] lets the worker skip a regeneration when nothing relevant changed.
 */
@Serializable
@Entity(
    tableName = "suggestion_batch",
    indices = [Index(value = ["generatedAtMillis"], name = "idx_batch_generated")],
)
data class SuggestionBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val generatedAtMillis: Long,
    val horizonStartDay: Long,
    val horizonEndDay: Long,
    val phase: TrainingPhase,
    val weeklyLoadTarget: Double,
    val inputsHash: String,
    val status: SuggestionStatus = SuggestionStatus.PROPOSED,
)
