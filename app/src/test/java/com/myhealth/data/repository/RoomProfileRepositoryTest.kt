package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.dao.ProfileDao
import com.myhealth.data.db.entity.ProfileEntity
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.Sex
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [RoomProfileRepository] against an in-memory fake DAO (PLAN P1.7) — no Room,
 * so they run in the JVM test task.
 */
class RoomProfileRepositoryTest {

    private val dao = FakeProfileDao()
    private val repo = RoomProfileRepository(dao, Dispatchers.Unconfined)

    @Test
    fun observe_profile_emits_null_when_nothing_is_stored() = runTest {
        assertThat(repo.observeProfile().first()).isNull()
    }

    @Test
    fun upsert_then_observe_emits_the_stored_profile() = runTest {
        val result = repo.upsert(profile(displayName = "Robert"))

        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        val observed = repo.observeProfile().first()
        assertThat(observed?.displayName).isEqualTo("Robert")
        assertThat(observed?.heightCm).isEqualTo(182.0)
        assertThat(observed?.neatLevel).isEqualTo(NeatLevel.ACTIVE)
    }

    @Test
    fun upsert_forces_the_singleton_row_id() = runTest {
        repo.upsert(profile(id = 7L))

        assertThat(dao.rows.value?.id).isEqualTo(ProfileEntity.SINGLETON_ID)
        assertThat(repo.getProfile()?.id).isEqualTo(ProfileEntity.SINGLETON_ID)
    }

    @Test
    fun upsert_overwrites_the_previous_profile_instead_of_adding_a_row() = runTest {
        repo.upsert(profile(displayName = "Old"))
        repo.upsert(profile(displayName = "New"))

        assertThat(dao.upsertCount).isEqualTo(2)
        assertThat(repo.getProfile()?.displayName).isEqualTo("New")
    }

    @Test
    fun a_failing_write_is_reported_as_a_storage_error() = runTest {
        dao.failWith = IOException("disk full")

        val result = repo.upsert(profile())

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error).isInstanceOf(AppError.Storage::class.java)
    }

    private fun profile(id: Long = ProfileEntity.SINGLETON_ID, displayName: String = "Robert") = Profile(
        id = id,
        displayName = displayName,
        sex = Sex.MALE,
        birthDay = Fixtures.epochDay("1996-04-02"),
        heightCm = 182.0,
        neatLevel = NeatLevel.ACTIVE,
        createdAtMillis = Fixtures.millis("2026-09-12T08:00:00Z"),
        updatedAtMillis = Fixtures.millis("2026-09-12T08:00:00Z"),
    )
}

/** Minimal in-memory [ProfileDao]: one nullable row, plus an injectable failure. */
private class FakeProfileDao : ProfileDao {

    val rows = MutableStateFlow<ProfileEntity?>(null)
    var upsertCount = 0
    var failWith: Throwable? = null

    override suspend fun upsert(entity: ProfileEntity) {
        failWith?.let { throw it }
        upsertCount++
        rows.value = entity
    }

    override suspend fun getById(id: Long): ProfileEntity? = rows.value?.takeIf { it.id == id }

    override fun observeProfile(): Flow<ProfileEntity?> = rows.map { it }

    override suspend fun count(): Int = if (rows.value == null) 0 else 1

    override suspend fun deleteById(id: Long) {
        if (rows.value?.id == id) rows.value = null
    }
}
