package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.QuantityUnit
import kotlinx.serialization.Serializable

/**
 * `meal_log_item` (PLAN §2.2.5) — owned by its meal log (`CASCADE`); the ingredient link is soft
 * (`SET_NULL`).
 *
 * The nutrient columns are **denormalized absolute snapshots** for the logged [quantity],
 * computed at log time. Rationale (§2.2.5): editing an ingredient later must not rewrite history,
 * and deleting one must not make a past day's totals unreadable — hence [nameSnapshot] too.
 */
@Serializable
@Entity(
    tableName = "meal_log_item",
    foreignKeys = [
        ForeignKey(
            entity = MealLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealLogId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = IngredientEntity::class,
            parentColumns = ["id"],
            childColumns = ["ingredientId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["mealLogId"], name = "idx_meal_item_log"),
        Index(value = ["ingredientId"], name = "idx_meal_item_ingredient"),
    ],
)
data class MealLogItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val mealLogId: Long,
    val ingredientId: Long? = null,
    val nameSnapshot: String,
    val quantity: Double,
    val unit: QuantityUnit,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val sugarG: Double,
    val fatG: Double,
    val satFatG: Double,
    val fiberG: Double,
    val saltG: Double,
)
