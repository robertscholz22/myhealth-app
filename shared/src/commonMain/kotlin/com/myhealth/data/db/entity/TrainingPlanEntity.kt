package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.PlanStatus
import kotlinx.serialization.Serializable

/**
 * `training_plan` (PLAN §2.2.4). At most one `ACTIVE` plan exists — enforced in the repository,
 * not by a constraint, so importing a backup can land rows in any order.
 */
@Serializable
@Entity(
    tableName = "training_plan",
    indices = [Index(value = ["status"], name = "idx_plan_status")],
)
data class TrainingPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val startDay: Long,
    val endDay: Long,
    val status: PlanStatus,
    val primaryGoalId: Long? = null,
    val notes: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
