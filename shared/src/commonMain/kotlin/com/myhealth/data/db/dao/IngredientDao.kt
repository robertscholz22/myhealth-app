package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.IngredientEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `ingredient` (PLAN §2.2.5).
 *
 * [search] is a plain `LIKE` scan over name and brand, used for queries too short to tokenise;
 * [searchFts] is the P8.5 full-text path over `ingredient_fts`. Archived rows are excluded
 * everywhere except [getById].
 */
@Dao
interface IngredientDao {

    @Upsert
    suspend fun upsert(entity: IngredientEntity): Long

    @Query("SELECT * FROM ingredient WHERE id = :id")
    suspend fun getById(id: Long): IngredientEntity?

    @Query("SELECT * FROM ingredient WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<IngredientEntity>

    @Query("DELETE FROM ingredient WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "SELECT * FROM ingredient WHERE archived = 0 AND (" +
            "name LIKE '%' || :q || '%' OR brand LIKE '%' || :q || '%'" +
            ") ORDER BY isFavorite DESC, useCount DESC, name ASC LIMIT :limit",
    )
    fun search(q: String, limit: Int): Flow<List<IngredientEntity>>

    /**
     * Full-text search (P8.5). [match] is an FTS4 MATCH expression — the repository builds it as
     * one `term*` prefix term per token, so "haf mil" finds "Hafermilch". The join is on the FTS
     * `rowid`, which for an external-content table is `ingredient.id`. Ordering matches [search]
     * so the two paths are indistinguishable to the user.
     */
    @Query(
        "SELECT ingredient.* FROM ingredient " +
            "JOIN ingredient_fts ON ingredient_fts.rowid = ingredient.id " +
            "WHERE ingredient.archived = 0 AND ingredient_fts MATCH :match " +
            "ORDER BY ingredient.isFavorite DESC, ingredient.useCount DESC, ingredient.name ASC " +
            "LIMIT :limit",
    )
    fun searchFts(match: String, limit: Int): Flow<List<IngredientEntity>>

    @Query(
        "SELECT * FROM ingredient WHERE archived = 0 AND lastUsedAtMillis IS NOT NULL " +
            "ORDER BY lastUsedAtMillis DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<IngredientEntity>>

    /**
     * Every non-archived ingredient, most recently used first and never-used ones last (SQLite
     * has no `NULLS LAST`, hence the leading `lastUsedAtMillis IS NULL`). This is what an empty
     * search query lists, so an ingredient that was just created but never logged is visible
     * (BUG-2); [observeRecent] stays the used-only list behind the Add-food "Recents" tab.
     */
    @Query(
        "SELECT * FROM ingredient WHERE archived = 0 " +
            "ORDER BY lastUsedAtMillis IS NULL, lastUsedAtMillis DESC, name ASC LIMIT :limit",
    )
    fun observeAllActive(limit: Int): Flow<List<IngredientEntity>>

    @Query("SELECT * FROM ingredient WHERE archived = 0 AND isFavorite = 1 ORDER BY name ASC")
    fun observeFavorites(): Flow<List<IngredientEntity>>

    @Query("SELECT * FROM ingredient WHERE archived = 1 ORDER BY name ASC LIMIT :limit")
    fun observeArchived(limit: Int): Flow<List<IngredientEntity>>

    @Query("SELECT * FROM ingredient WHERE barcode = :barcode LIMIT 1")
    suspend fun getByBarcode(barcode: String): IngredientEntity?

    @Query(
        "UPDATE ingredient SET useCount = useCount + 1, lastUsedAtMillis = :atMillis, " +
            "updatedAtMillis = :atMillis WHERE id = :id",
    )
    suspend fun markUsed(id: Long, atMillis: Long)
}
