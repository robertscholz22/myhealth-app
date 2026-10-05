package com.myhealth.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.myhealth.data.db.entity.MealTemplateEntity
import com.myhealth.data.db.entity.MealTemplateItemEntity

/** One `meal_template` row with its owned `meal_template_item` rows (PLAN §2.2.5). */
data class MealTemplateWithItems(
    @Embedded val template: MealTemplateEntity,
    @Relation(parentColumn = "id", entityColumn = "templateId")
    val items: List<MealTemplateItemEntity>,
)
