package com.myhealth.domain.repository

import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `goal` (PLAN §2.2.4, P6.1). Invariant enforced by the implementation: only one goal may have
 * `priority = 1` — [setPrimary] (and an [upsert] with `priority = 1`) demotes the others.
 */
interface GoalRepository {

    fun observeAll(): Flow<List<Goal>>

    fun observeByStatus(status: GoalStatus): Flow<List<Goal>>

    fun observePrimary(): Flow<Goal?>

    suspend fun getById(id: Long): Goal?

    suspend fun upsert(goal: Goal): Outcome<Long>

    suspend fun setStatus(id: Long, status: GoalStatus): Outcome<Unit>

    suspend fun setPrimary(id: Long): Outcome<Unit>

    suspend fun delete(id: Long): Outcome<Unit>
}
