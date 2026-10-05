package com.myhealth.data.repository

import com.myhealth.data.db.dao.ProfileDao
import com.myhealth.data.db.entity.ProfileEntity
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.model.Profile
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [ProfileRepository] (PLAN P1.7) over the single-row `profile` table.
 *
 * Room's own `Flow` queries already run off the main thread, so only the one-shot calls take an
 * explicit dispatcher — `Dispatchers.IO` by default, injectable so unit tests stay deterministic
 * (§1.4: never in the ViewModel, always here).
 */
class RoomProfileRepository(
    private val profileDao: ProfileDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProfileRepository {

    override fun observeProfile(): Flow<Profile?> =
        profileDao.observeProfile().map { it?.toDomain() }

    override suspend fun getProfile(): Profile? = withContext(ioDispatcher) {
        profileDao.getById(PROFILE_ID)?.toDomain()
    }

    override suspend fun upsert(profile: Profile): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { profileDao.upsert(profile.copy(id = PROFILE_ID).toEntity()) }
    }

    private companion object {
        /** The `profile` table always holds exactly one row (§2.2.1). */
        const val PROFILE_ID = ProfileEntity.SINGLETON_ID
    }
}
