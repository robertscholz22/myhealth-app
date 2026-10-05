package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.ProfileEntity
import kotlinx.coroutines.flow.Flow

/** DAO for the single-row `profile` table (PLAN §2.2.1). */
@Dao
interface ProfileDao {

    @Upsert
    suspend fun upsert(entity: ProfileEntity)

    @Query("SELECT * FROM profile WHERE id = :id")
    suspend fun getById(id: Long): ProfileEntity?

    @Query("SELECT * FROM profile WHERE id = 1")
    fun observeProfile(): Flow<ProfileEntity?>

    @Query("SELECT COUNT(*) FROM profile")
    suspend fun count(): Int

    @Query("DELETE FROM profile WHERE id = :id")
    suspend fun deleteById(id: Long)
}
