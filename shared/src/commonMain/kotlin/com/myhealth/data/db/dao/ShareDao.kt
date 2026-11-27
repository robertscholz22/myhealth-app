package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.ShareConfigEntity
import com.myhealth.data.db.entity.ShareLinkEntity
import kotlinx.coroutines.flow.Flow

/** P27: the family library connection and the per-row share links. */
@Dao
interface ShareDao {

    @Query("SELECT * FROM share_config WHERE id = 1")
    fun observeConfig(): Flow<ShareConfigEntity?>

    @Query("SELECT * FROM share_config WHERE id = 1")
    suspend fun getConfig(): ShareConfigEntity?

    @Upsert
    suspend fun upsertConfig(row: ShareConfigEntity)

    @Query("DELETE FROM share_config")
    suspend fun deleteConfig()

    @Query("SELECT * FROM share_link")
    fun observeLinks(): Flow<List<ShareLinkEntity>>

    @Query("SELECT * FROM share_link")
    suspend fun getLinks(): List<ShareLinkEntity>

    @Query("SELECT * FROM share_link WHERE kind = :kind AND localKey = :localKey")
    suspend fun getLink(kind: String, localKey: String): ShareLinkEntity?

    @Query("SELECT * FROM share_link WHERE shareId = :shareId")
    suspend fun getLinkByShareId(shareId: String): ShareLinkEntity?

    /** Insert or replace by (kind, localKey); never call directly — see [upsertLink]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceLink(row: ShareLinkEntity)

    /**
     * Stores [row]; a share id already linked to another local row is refused. (Room's `@Upsert`
     * turned that unique-index clash into an update of nothing, and REPLACE would quietly unlink
     * the other row.)
     */
    @Transaction
    suspend fun upsertLink(row: ShareLinkEntity) {
        row.shareId?.let { id ->
            getLinkByShareId(id)?.let { other ->
                check(other.kind == row.kind && other.localKey == row.localKey) {
                    "share id already linked to ${other.kind} ${other.localKey}"
                }
            }
        }
        replaceLink(row)
    }

    @Query("DELETE FROM share_link WHERE kind = :kind AND localKey = :localKey")
    suspend fun deleteLink(kind: String, localKey: String)
}
