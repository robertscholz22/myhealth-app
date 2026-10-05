package com.myhealth.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.myhealth.data.db.entity.MealLogEntity
import com.myhealth.data.db.entity.MealLogItemEntity

/**
 * One `meal_log` row with its owned `meal_log_item` rows (PLAN §2.2.5). Queries returning this
 * must be annotated `@Transaction` so the parent and the children are read consistently.
 */
data class MealLogWithItems(
    @Embedded val log: MealLogEntity,
    @Relation(parentColumn = "id", entityColumn = "mealLogId")
    val items: List<MealLogItemEntity>,
)
