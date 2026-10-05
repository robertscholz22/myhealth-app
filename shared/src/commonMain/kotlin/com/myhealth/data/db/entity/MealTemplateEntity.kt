package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.MealSlot
import kotlinx.serialization.Serializable

/** `meal_template` (PLAN §2.2.5) — a reusable set of ingredients with quantities. */
@Serializable
@Entity(
    tableName = "meal_template",
    indices = [Index(value = ["lastUsedAtMillis"], name = "idx_template_last_used")],
)
data class MealTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val defaultSlot: MealSlot? = null,
    val note: String? = null,
    val isFavorite: Boolean = false,
    val useCount: Int = 0,
    val lastUsedAtMillis: Long? = null,
    val archived: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
