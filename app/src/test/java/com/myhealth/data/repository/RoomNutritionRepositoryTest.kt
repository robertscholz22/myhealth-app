package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.healthconnect.FakeActivityRepository
import com.myhealth.data.healthconnect.FakeBodyRepository
import com.myhealth.data.healthconnect.FakeHealthRepository
import com.myhealth.domain.engine.nutrition.NutritionTargetEngine
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [RoomNutritionRepository.ensureTarget] against in-memory fakes (PLAN P4.12): it must compute on
 * the first call, do nothing at all on a second call with unchanged inputs, and recompute after a
 * weight change. Plus the water CRUD of P4.13.
 */
class RoomNutritionRepositoryTest {

    private val today = Fixtures.epochDay("2026-09-12")
    private val clock = MutableClock(Fixtures.millis("2026-09-12T08:00:00Z"))

    private val dao = FakeNutritionDao()
    private val profileRepo = FakeTargetProfileRepository(profile())
    private val bodyRepo = FakeBodyRepository()
    private val healthRepo = FakeHealthRepository()
    private val activityRepo = FakeActivityRepository()
    private val planRepo = FakeTargetPlanRepository()
    private val calendarRepo = FakeTargetCalendarRepository()

    private val repo = RoomNutritionRepository(
        nutritionDao = dao,
        profileRepo = profileRepo,
        bodyRepo = bodyRepo,
        healthRepo = healthRepo,
        activityRepo = activityRepo,
        planRepo = planRepo,
        calendarRepo = calendarRepo,
        engine = NutritionTargetEngine(Fixtures.TZ),
        clock = clock,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun ensure_target_computes_and_stores_a_snapshot_on_the_first_call() = runTest {
        bodyRepo.measurements += measurement(80.0)

        val outcome = repo.ensureTarget(today)

        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
        val target = (outcome as Outcome.Ok).value
        assertThat(target.day).isEqualTo(today)
        assertThat(target.bmrKcal).isEqualTo(1780)
        assertThat(target.tdeeKcal).isEqualTo(2225)
        assertThat(target.kcal).isEqualTo(2230)
        assertThat(target.dayType).isEqualTo(DayType.REST)
        assertThat(target.inputsHash).isNotEmpty()
        assertThat(target.computedAtMillis).isEqualTo(clock.millis())
        assertThat(dao.writeCount).isEqualTo(1)
        assertThat(repo.observeTarget(today).first()?.kcal).isEqualTo(2230)
    }

    @Test
    fun ensure_target_skips_the_recompute_when_the_inputs_hash_is_unchanged() = runTest {
        bodyRepo.measurements += measurement(80.0)
        val first = (repo.ensureTarget(today) as Outcome.Ok).value

        clock.advanceMillis(60 * 60 * 1000L)
        val second = (repo.ensureTarget(today) as Outcome.Ok).value

        assertThat(dao.writeCount).isEqualTo(1)
        assertThat(second.inputsHash).isEqualTo(first.inputsHash)
        assertThat(second.computedAtMillis).isEqualTo(first.computedAtMillis)
    }

    @Test
    fun ensure_target_recomputes_after_a_weight_change() = runTest {
        bodyRepo.measurements += measurement(80.0)
        val first = (repo.ensureTarget(today) as Outcome.Ok).value

        clock.advanceMillis(60 * 60 * 1000L)
        bodyRepo.measurements += measurement(74.0, atIso = "2026-09-12T07:00:00Z")
        val second = (repo.ensureTarget(today) as Outcome.Ok).value

        assertThat(dao.writeCount).isEqualTo(2)
        assertThat(second.inputsHash).isNotEqualTo(first.inputsHash)
        assertThat(second.kcal).isLessThan(first.kcal)
        assertThat(second.bmrKcal).isEqualTo(1720)
        assertThat(second.computedAtMillis).isEqualTo(clock.millis())
    }

    @Test
    fun ensure_target_recomputes_when_the_day_type_changes() = runTest {
        bodyRepo.measurements += measurement(80.0)
        val rest = (repo.ensureTarget(today) as Outcome.Ok).value

        calendarRepo.occurrences = listOf(matchOn(today))
        val matchDay = (repo.ensureTarget(today) as Outcome.Ok).value

        assertThat(matchDay.dayType).isEqualTo(DayType.MATCH_DAY)
        assertThat(matchDay.carbsG).isGreaterThan(rest.carbsG)
        assertThat(dao.writeCount).isEqualTo(2)
    }

    @Test
    fun ensure_target_fails_with_a_validation_error_before_onboarding() = runTest {
        profileRepo.profiles.value = null

        val outcome = repo.ensureTarget(today)

        assertThat(outcome).isInstanceOf(Outcome.Err::class.java)
        assertThat((outcome as Outcome.Err).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(dao.writeCount).isEqualTo(0)
    }

    @Test
    fun water_is_summed_per_day_and_deletable() = runTest {
        val id = (repo.addWater(today, 250, atMinuteOfDay = 8 * 60) as Outcome.Ok).value
        repo.addWater(today, 500, atMinuteOfDay = 12 * 60)
        repo.addWater(today + 1, 750, atMinuteOfDay = 9 * 60)

        assertThat(repo.observeWaterTotalMl(today).first()).isEqualTo(750)
        assertThat(repo.observeWaterLogs(today).first().map { it.ml }).containsExactly(250, 500).inOrder()

        repo.deleteWater(id)

        assertThat(repo.observeWaterTotalMl(today).first()).isEqualTo(500)
        assertThat(repo.observeWaterTotalMl(today + 1).first()).isEqualTo(750)
    }

    @Test
    fun water_rejects_non_positive_and_implausible_amounts() = runTest {
        assertThat(repo.addWater(today, 0, null)).isInstanceOf(Outcome.Err::class.java)
        assertThat(repo.addWater(today, -100, null)).isInstanceOf(Outcome.Err::class.java)
        assertThat(repo.addWater(today, 9_000, null)).isInstanceOf(Outcome.Err::class.java)
        assertThat(repo.observeWaterTotalMl(today).first()).isEqualTo(0)
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun profile(): Profile = Profile(
        displayName = "Test",
        sex = Sex.MALE,
        birthDay = Fixtures.epochDay("1996-09-12"),
        heightCm = 180.0,
        neatLevel = NeatLevel.DESK,
        goalWeightKg = 80.0,
        goalPaceKgPerWeek = 0.0,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    private fun measurement(
        weightKg: Double,
        atIso: String = "2026-09-12T06:00:00Z",
    ): BodyMeasurement = BodyMeasurement(
        id = 0L,
        measuredAtMillis = Fixtures.millis(atIso),
        day = Fixtures.epochDay(atIso.substringBefore('T')),
        weightKg = weightKg,
        bodyFatPercent = null,
        muscleMassKg = null,
        boneMassKg = null,
        bodyWaterPercent = null,
        source = ActivitySource.MANUAL,
    )

    private fun matchOn(day: Long): EventOccurrence = EventOccurrence(
        eventId = 1L,
        occurrenceDay = day,
        type = EventType.SOCCER_MATCH,
        effectiveTitle = "League match",
        effectiveStartMinuteOfDay = 19 * 60,
        effectiveDurationMin = 100,
        isOverride = false,
        linkedActivityId = null,
        sportType = null,
        targetDistanceMeters = null,
        isKeyEvent = true,
    )
}

/** A [Clock] whose "now" the test moves by hand, so `computedAtMillis` is observable. */
internal class MutableClock(
    private var nowMillis: Long,
    private val zone: ZoneId = Fixtures.ZONE,
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(nowMillis, zone)
    override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)
    fun advanceMillis(delta: Long) {
        nowMillis += delta
    }
}
