package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4

/**
 * `ingredient_fts` (PLAN P8.5) — an FTS4 index over the searchable text of [IngredientEntity].
 *
 * It is an **external-content** table (`contentEntity`): the rows live in `ingredient` and only the
 * inverted index is stored here, so there is no second copy of the data to keep consistent by hand.
 * Room generates the `INSERT`/`UPDATE`/`DELETE` sync triggers; `MIGRATION_1_2` creates them for an
 * existing database and rebuilds the index once from the rows that are already there.
 *
 * The `rowid` of this table is `ingredient.id`, which is what the join in
 * [com.myhealth.data.db.dao.IngredientDao.searchFts] matches on.
 */
@Entity(tableName = "ingredient_fts")
@Fts4(contentEntity = IngredientEntity::class)
data class IngredientFtsEntity(
    val name: String,
    val brand: String?,
)
