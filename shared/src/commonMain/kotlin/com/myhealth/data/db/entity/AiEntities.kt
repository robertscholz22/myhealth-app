package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * `ai_config` (P28, DB v14) — the single row (`id = 1`) for AI planning: the relay this phone
 * registered with, its space and token, and which data groups it syncs. In the JSON backup like
 * `share_config`, so a restored phone keeps its paired Claude / ChatGPT connector.
 */
@Serializable
@Entity(tableName = "ai_config")
data class AiConfigEntity(
    @PrimaryKey val id: Int = 1,
    val relayUrl: String,
    val spaceId: String,
    val spaceToken: String,
    /** Comma-separated [com.myhealth.domain.ai.AiDataGroup] names. */
    val groups: String,
    val connectedAtMillis: Long,
    val lastSyncAtMillis: Long? = null,
    /** The last sync's failure, shown on the AI planning screen; null after a good sync. */
    val lastError: String? = null,
)

/**
 * `ai_link` (P28, DB v14) — a row the AI created: its `ai-…` id from the conversation and the local
 * row ([kind] `EXERCISE` / `WORKOUT` / `SESSION`, [localKey] its primary key as text). The AI may
 * only change or delete workouts that have a link.
 */
@Serializable
@Entity(
    tableName = "ai_link",
    indices = [Index(value = ["kind", "localKey"], unique = true, name = "uq_ai_link_local")],
)
data class AiLinkEntity(
    @PrimaryKey val aiId: String,
    val kind: String,
    val localKey: String,
    val createdAtMillis: Long,
)

/**
 * `ai_change` (P28, DB v14) — one op from the relay and what the phone did with it: the "AI
 * changes" log. Ops of one sync form a [batchId]; Undo walks a batch backwards using [beforeJson].
 * [opId] is unique, so an op pulled twice (a lost ack) is applied once.
 */
@Serializable
@Entity(
    tableName = "ai_change",
    indices = [
        Index(value = ["opId"], unique = true, name = "uq_ai_change_op"),
        Index(value = ["batchId"], name = "idx_ai_change_batch"),
    ],
)
data class AiChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val batchId: String,
    val opId: String,
    /** The op's wire type (`schedule_session`, …). */
    val type: String,
    /** `APPLIED`, `REJECTED` or `UNDONE`. */
    val result: String,
    /** One line for the log: "Scheduled Tempo run on Wed 3 Dec". */
    val summary: String,
    /** Why the phone refused the op ([result] `REJECTED`). */
    val reason: String? = null,
    /** `EXERCISE` / `WORKOUT` / `SESSION` / `EQUIPMENT`, and the local row it touched. */
    val kind: String? = null,
    val localKey: String? = null,
    /** The row before the change (JSON), null for an insert: what Undo restores. */
    val beforeJson: String? = null,
    val appliedAtMillis: Long,
)
