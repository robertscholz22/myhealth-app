package com.myhealth.domain.repository

import com.myhealth.domain.ai.AiConnection
import com.myhealth.domain.ai.AiDataGroup
import com.myhealth.domain.ai.AiSetup
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** One change the AI made, as the AI changes log shows it. */
data class AiChange(
    val id: Long,
    val batchId: String,
    val opId: String,
    val summary: String,
    val result: AiChangeResult,
    val reason: String?,
    val appliedAtMillis: Long,
)

enum class AiChangeResult { APPLIED, REJECTED, UNDONE }

/** What one sync did. */
data class AiSyncResult(val applied: Int, val rejected: Int, val batchId: String?)

/** The relay's view of this phone's space. */
data class AiRelayStatus(val pending: Int, val connectors: List<String>, val snapshotAtMillis: Long?)

data class AiPairingCode(val code: String, val expiresAtMillis: Long)

/**
 * P28: AI planning through the relay. Off until [connect]; every write goes through the content
 * repositories, the AI's scope is decided by `AiOpPlanner` here on the phone.
 */
interface AiPlanningRepository {
    val setup: Flow<AiSetup?>
    val changes: Flow<List<AiChange>>

    /** Ids of local rows the AI created, as `kind:localKey` (`SESSION:12`), for the "Planned with AI" badge. */
    val createdByAi: Flow<Set<String>>

    suspend fun connect(connection: AiConnection, groups: Set<AiDataGroup>): Outcome<Unit>
    suspend fun setGroups(groups: Set<AiDataGroup>): Outcome<Unit>

    /** Pull → apply → ack → push snapshot. One sync at a time; a second caller waits for the first. */
    suspend fun sync(): Outcome<AiSyncResult>
    suspend fun pairingCode(): Outcome<AiPairingCode>
    suspend fun relayStatus(): Outcome<AiRelayStatus>

    /** Reverts the applied changes of [batchId], newest first; returns how many were undone. */
    suspend fun undo(batchId: String): Outcome<Int>

    /** Deletes the space on the relay (best effort) and every local AI row except the content. */
    suspend fun disconnect(): Outcome<Unit>
}

/** For app start and the background sync: nothing to do while AI planning is off. */
suspend fun AiPlanningRepository.syncIfConnected() {
    if (setup.first() != null) sync()
}
