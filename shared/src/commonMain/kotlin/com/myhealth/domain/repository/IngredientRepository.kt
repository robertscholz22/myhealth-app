package com.myhealth.domain.repository

import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `ingredient` (PLAN §2.2.5, P4.2). [search] is undebounced — the ViewModel debounces (250 ms).
 * [deleteOrArchive] archives instead of deleting when any `meal_log_item` still references the
 * row, so logged history keeps its snapshot provenance.
 */
interface IngredientRepository {

    fun search(query: String, limit: Int): Flow<List<Ingredient>>

    fun observeRecent(limit: Int): Flow<List<Ingredient>>

    fun observeFavorites(): Flow<List<Ingredient>>

    /** Archived rows only — backs the Ingredients list's "show archived" switch (§4.2). */
    fun observeArchived(limit: Int): Flow<List<Ingredient>>

    suspend fun getById(id: Long): Ingredient?

    suspend fun getByIds(ids: List<Long>): List<Ingredient>

    suspend fun getByBarcode(barcode: String): Ingredient?

    suspend fun upsert(ingredient: Ingredient): Outcome<Long>

    /** Bumps `useCount` and `lastUsedAtMillis` — drives the "recents" list. */
    suspend fun markUsed(id: Long): Outcome<Unit>

    suspend fun setFavorite(id: Long, favorite: Boolean): Outcome<Unit>

    suspend fun deleteOrArchive(id: Long): Outcome<Unit>
}
