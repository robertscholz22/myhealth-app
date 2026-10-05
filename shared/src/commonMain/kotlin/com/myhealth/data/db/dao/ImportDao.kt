package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.ImportRecordEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `import_record` (PLAN §2.2.6). The file hash is the duplicate-import guard. */
@Dao
interface ImportDao {

    @Upsert
    suspend fun upsert(entity: ImportRecordEntity): Long

    @Query("SELECT * FROM import_record WHERE id = :id")
    suspend fun getById(id: Long): ImportRecordEntity?

    @Query("DELETE FROM import_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM import_record WHERE fileHashSha256 = :hash LIMIT 1")
    suspend fun getByHash(hash: String): ImportRecordEntity?

    @Query("SELECT * FROM import_record ORDER BY importedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ImportRecordEntity>>

    /**
     * BUG-19 repair: replaces every `errorsJson` longer than [maxChars] with [replacement].
     * `length()` is evaluated inside SQLite, so the oversized rows are never loaded into a cursor.
     */
    @Query("UPDATE import_record SET errorsJson = :replacement WHERE length(errorsJson) > :maxChars")
    suspend fun replaceOversizedErrors(maxChars: Int, replacement: String): Int
}
