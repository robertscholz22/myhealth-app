package com.myhealth.domain.repository

import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.SuggestionBatch
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `suggestion_batch` + `suggested_session` (PLAN §2.2.4, P6.5).
 *
 * [generate] gathers the engine inputs, runs `SuggestionEngine` (§3.5), writes a new batch and
 * marks any previous `PROPOSED` batch `SUPERSEDED`. [accept] copies the chosen sessions into
 * `planned_session` (status `PLANNED`, `sourceSuggestionId` set) and closes the batch
 * (`ACCEPTED`, or `REJECTED` when every session was rejected — POLISH-9), so the review screen and
 * the Today card stop offering a batch that has already been dealt with.
 *
 * [observeStale] / [markProposedStale] are POLISH-8: a calendar edit under an open `PROPOSED`
 * batch makes that batch's assumptions wrong, so the UI shows a "Calendar changed — regenerate"
 * hint until the next [generate].
 */
interface SuggestionRepository {

    fun observeLatestBatch(): Flow<SuggestionBatch?>

    /** True while an open `PROPOSED` batch predates a calendar change (POLISH-8). */
    fun observeStale(): Flow<Boolean>

    fun observeSessions(batchId: Long): Flow<List<SuggestedSession>>

    suspend fun getBatch(id: Long): SuggestionBatch?

    suspend fun generate(horizonDays: Int): Outcome<SuggestionBatch>

    /**
     * How many unlocked, still-`PLANNED` sessions inside the batch's horizon would be replaced by
     * accepting it (BUG-10): a proposal replaces the week, locked and completed sessions stay.
     */
    suspend fun countReplaceableSessions(batchId: Long): Int

    suspend fun accept(sessionIds: List<Long>): Outcome<Unit>

    suspend fun reject(sessionIds: List<Long>): Outcome<Unit>

    suspend fun supersedeProposed(): Outcome<Unit>

    /**
     * Flags the open `PROPOSED` batch as generated against a calendar that has since changed
     * (POLISH-8). A no-op when the latest batch is not `PROPOSED` — there is nothing to regenerate.
     */
    suspend fun markProposedStale(): Outcome<Unit>
}
