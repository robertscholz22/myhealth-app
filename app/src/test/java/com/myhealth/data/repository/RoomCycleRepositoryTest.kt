package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.AppSettings
import com.myhealth.domain.model.CycleEntry
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.Sex
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [RoomCycleRepository] over an in-memory [FakeCycleDao] (PLAN §5 P11.1). What is tested here is
 * what is *not* in the engine and not in the schema: the one-entry-per-start-day fold, the
 * end-before-start guard, and the `settings || sex == FEMALE` tracking gate.
 */
class RoomCycleRepositoryTest {

    private val base = Fixtures.epochDay("2026-01-05")
    private val clock = Fixtures.fixedClock("2026-01-05T09:00:00Z")

    private val dao = FakeCycleDao()
    private val profileRepo = FakeTargetProfileRepository(profile(Sex.MALE))
    private val settingsRepo = FakeLoadSettingsRepository()

    private val repo = RoomCycleRepository(
        cycleDao = dao,
        profileRepo = profileRepo,
        settingsRepo = settingsRepo,
        clock = clock,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun profile(sex: Sex): Profile = Profile(
        id = 1L,
        displayName = "Test",
        sex = sex,
        birthDay = Fixtures.epochDay("1995-03-04"),
        heightCm = 170.0,
        neatLevel = NeatLevel.LIGHT_ACTIVE,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    private fun entry(startOffset: Long, lengthDays: Int? = null, id: Long = 0L): CycleEntry =
        CycleEntry(
            id = id,
            periodStartDay = base + startOffset,
            periodEndDay = lengthDays?.let { base + startOffset + it - 1 },
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )

    private fun Outcome<Long>.id(): Long = (this as Outcome.Ok).value

    @Test
    fun upsert_stamps_timestamps_and_is_observable_in_start_order() = runTest {
        val second = repo.upsert(entry(28)).id()
        val first = repo.upsert(entry(0)).id()

        val all = repo.observeAll().first()
        assertThat(all.map { it.id }).containsExactly(first, second).inOrder()
        assertThat(all.first().createdAtMillis).isEqualTo(clock.millis())
        assertThat(all.first().updatedAtMillis).isEqualTo(clock.millis())
        assertThat(repo.getById(first)?.periodStartDay).isEqualTo(base)
        assertThat(repo.getAll()).hasSize(2)
    }

    @Test
    fun logging_the_same_start_twice_edits_the_existing_entry() = runTest {
        val id = repo.upsert(entry(0)).id()

        // "Period ended" arrives as a fresh entry carrying the same start day.
        val again = repo.upsert(entry(0, lengthDays = 5))

        assertThat(again.id()).isEqualTo(id)
        assertThat(repo.getAll()).hasSize(1)
        assertThat(repo.getById(id)?.periodEndDay).isEqualTo(base + 4)
        assertThat(repo.getById(id)?.periodLengthDays).isEqualTo(5)
    }

    @Test
    fun an_end_before_the_start_is_refused() = runTest {
        val bad = repo.upsert(
            entry(10).copy(periodEndDay = base + 3),
        )

        assertThat(bad).isInstanceOf(Outcome.Err::class.java)
        assertThat(((bad as Outcome.Err).error as AppError.Validation).field).isEqualTo("periodEndDay")
        assertThat(repo.getAll()).isEmpty()
    }

    @Test
    fun status_and_forecast_follow_the_logged_entries() = runTest {
        assertThat(repo.statusFor(base)).isNull()
        assertThat(repo.observeForecast(base).first().cycles).isEmpty()

        listOf(0L, 28L, 56L).forEach { repo.upsert(entry(it, lengthDays = 5)) }

        val status = repo.observeStatus(base + 56).first()
        assertThat(status).isNotNull()
        assertThat(status!!.dayOfCycle).isEqualTo(1)
        assertThat(status.phase).isEqualTo(CyclePhase.MENSTRUAL)
        assertThat(status.nextPeriodStart).isEqualTo(base + 84)

        val horizon = repo.statusesFor(base + 56, base + 63)
        assertThat(horizon.keys).containsExactlyElementsIn((56L..62L).map { base + it })
        assertThat(horizon.getValue(base + 61).phase).isEqualTo(CyclePhase.FOLLICULAR)

        assertThat(repo.observeForecast(base + 56).first().cycles).hasSize(6)

        // Deleting the oldest entry drops one interval; the remaining two still anchor a status.
        repo.delete(repo.getAll().first().id)
        assertThat(repo.getAll()).hasSize(2)
        assertThat(repo.statusFor(base + 56)).isNotNull()
    }

    @Test
    fun tracking_is_on_for_a_female_profile_or_when_the_setting_is_set() = runTest {
        // MALE profile, flag off.
        assertThat(repo.isTrackingEnabled().first()).isFalse()

        // MALE profile, flag on: an explicit opt-in.
        settingsRepo.update { AppSettings(cycleTrackingEnabled = true) }
        assertThat(repo.isTrackingEnabled().first()).isTrue()

        // FEMALE profile, flag never written: on by default.
        settingsRepo.update { AppSettings(cycleTrackingEnabled = false) }
        profileRepo.profiles.value = profile(Sex.FEMALE)
        assertThat(repo.isTrackingEnabled().first()).isTrue()

        // No profile at all and no flag: nothing to track.
        profileRepo.profiles.value = null
        assertThat(repo.isTrackingEnabled().first()).isFalse()
    }
}
