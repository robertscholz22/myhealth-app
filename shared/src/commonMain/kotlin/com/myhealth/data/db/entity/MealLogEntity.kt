package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.MealSlot
import kotlinx.serialization.Serializable

/**
 * `meal_log` (PLAN §2.2.5) — one logged meal on one local [day].
 *
 * [templateId] is provenance only (`SET_NULL`): the items are **copied** into `meal_log_item` at
 * log time, so editing or deleting the template never rewrites history.
 */
@Serializable
@Entity(
    tableName = "meal_log",
    foreignKeys = [
        ForeignKey(
            entity = MealTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["day"], name = "idx_meal_log_day"),
        Index(value = ["templateId"], name = "idx_meal_log_template"),
    ],
)
data class MealLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val day: Long,
    val atMinuteOfDay: Int? = null,
    val slot: MealSlot,
    val name: String? = null,
    val templateId: Long? = null,
    val note: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
