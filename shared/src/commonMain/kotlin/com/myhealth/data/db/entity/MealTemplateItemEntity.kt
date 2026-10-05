package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.QuantityUnit
import kotlinx.serialization.Serializable

/**
 * `meal_template_item` (PLAN §2.2.5) — owned by its template (`CASCADE`), but the ingredient link
 * is `RESTRICT`: an ingredient still used by a template cannot be deleted out from under it.
 */
@Serializable
@Entity(
    tableName = "meal_template_item",
    foreignKeys = [
        ForeignKey(
            entity = MealTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = IngredientEntity::class,
            parentColumns = ["id"],
            childColumns = ["ingredientId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["templateId"], name = "idx_tpl_item_template"),
        Index(value = ["ingredientId"], name = "idx_tpl_item_ingredient"),
    ],
)
data class MealTemplateItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val templateId: Long,
    val ingredientId: Long,
    val quantity: Double,
    val unit: QuantityUnit,
    val sortOrder: Int = 0,
)
