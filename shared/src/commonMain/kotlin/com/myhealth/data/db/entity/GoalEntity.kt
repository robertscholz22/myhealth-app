package com.myhealth.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.GoalType
import kotlinx.serialization.Serializable

/** `goal` (PLAN §2.2.4). [priority] 1 = primary; the `(status, priority)` index drives the picker. */
@Serializable
@Entity(
    tableName = "goal",
    indices = [Index(value = ["status", "priority"], name = "idx_goal_status_priority")],
)
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val type: GoalType,
    val title: String,
    val targetDay: Long? = null,
    val targetDistanceMeters: Double? = null,
    val targetTimeSec: Int? = null,
    val targetWeightKg: Double? = null,
    val targetValue: Double? = null,
    val priority: Int = 1,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val linkedEventId: Long? = null,
    val notes: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** P19 (DB v8): race on `targetDay` (`1`, every pre-0.8.0 goal) or a deadline (`0`). */
    @ColumnInfo(defaultValue = "1")
    val isRace: Boolean = true,
)
