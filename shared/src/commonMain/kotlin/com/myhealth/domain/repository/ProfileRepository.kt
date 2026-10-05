package com.myhealth.domain.repository

import com.myhealth.domain.model.Profile
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * The single-row `profile` (PLAN §2.2.1). Reads are [Flow]s over cached data and never fail;
 * the one-shot [getProfile] exists for engines/workers that need a snapshot, not a stream (§1.4).
 */
interface ProfileRepository {

    /** Emits `null` until onboarding (P1.10) has written the profile. */
    fun observeProfile(): Flow<Profile?>

    suspend fun getProfile(): Profile?

    suspend fun upsert(profile: Profile): Outcome<Unit>
}
