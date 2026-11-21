package com.myhealth.data.video

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.healthconnect.FakeHealthRepository
import com.myhealth.data.repository.ActivityIngestor
import com.myhealth.data.repository.DirectTransactionRunner
import com.myhealth.data.repository.FakeActivityDao
import com.myhealth.data.repository.FakeLoadRepository
import com.myhealth.data.repository.FakeLoadSettingsRepository
import com.myhealth.data.repository.FakePlanDao
import com.myhealth.data.repository.FakeRideBestRepository
import com.myhealth.data.repository.FakeRunningBestRepository
import com.myhealth.data.repository.FakeTargetProfileRepository
import com.myhealth.data.repository.InMemoryStrengthRepository
import com.myhealth.data.repository.LoadRecomputeService
import com.myhealth.data.repository.RoomActivityRepository
import com.myhealth.data.repository.RoomPlanRepository
import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.engine.strength.video.VideoEffort
import com.myhealth.domain.engine.strength.video.VideoRow
import com.myhealth.domain.engine.strength.video.VideoWorkoutBuilder
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SportType
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** P26.5: done without the watch → estimate, recording → replacement, reopen → estimate gone. */
class VideoSessionServiceTest {

    private val clock = Fixtures.fixedClock("2026-09-12T18:00:00Z")
    private val today = Fixtures.epochDay("2026-09-12")

    private val activityDao = FakeActivityDao()
    private val activityRepo = RoomActivityRepository(
        activityDao, ActivityIngestor(activityDao, DirectTransactionRunner, clock), clock, ioDispatcher = Dispatchers.Unconfined,
    )
    private val planRepo = RoomPlanRepository(FakePlanDao(), clock, Dispatchers.Unconfined)
    private val loadRepo = FakeLoadRepository()
    private val strengthRepo = InMemoryStrengthRepository()
    private val service = VideoSessionService(planRepo, activityRepo, loadRepo, strengthRepo, clock)
    private val recompute = LoadRecomputeService(
        activityDao = activityDao,
        activityRepo = activityRepo,
        loadRepo = loadRepo,
        runningBestRepo = FakeRunningBestRepository(),
        rideBestRepo = FakeRideBestRepository(),
        profileRepo = FakeTargetProfileRepository(
            Profile(displayName = "T", sex = Sex.FEMALE, birthDay = 7000L, heightCm = 168.0, createdAtMillis = 0L, updatedAtMillis = 0L),
        ),
        healthRepo = FakeHealthRepository(),
        settingsRepo = FakeLoadSettingsRepository(),
        clock = clock,
        planRepo = planRepo,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private suspend fun videoWorkoutId(): Long {
        val rows = listOf(VideoRow(ExerciseCatalog.byId("PUSH_UP")!!, 3, null, 40, false, "Push-ups 40s"))
        val w = VideoWorkoutBuilder.build(null, "Video A", "https://vimeo.com/1", null, 20, "Push-ups 40s", rows, null, 0L)
        return (strengthRepo.upsertWorkout(w) as Outcome.Ok).value
    }

    @Test
    fun done_today_creates_a_linked_estimate_whose_load_comes_from_rpe() = runTest {
        val workoutId = videoWorkoutId()
        assertThat(service.doneToday(workoutId, 20, VideoEffort.HARD)).isInstanceOf(Outcome.Ok::class.java)

        val session = planRepo.getSessions(today, today).single()
        assertThat(session.status).isEqualTo(PlannedStatus.COMPLETED)
        assertThat(session.workoutId).isEqualTo(workoutId)
        val activity = activityRepo.getById(session.linkedActivityId!!)!!
        assertThat(activity.rpe).isEqualTo(7)
        assertThat(activity.durationSec).isEqualTo(1200)
        assertThat(activity.primarySource).isEqualTo(ActivitySource.MANUAL)

        recompute.recompute(today)
        val scored = activityRepo.getById(activity.id)!!
        assertThat(scored.loadMethod).isEqualTo(LoadMethod.RPE_ESTIMATE)
        assertThat(scored.trimp!!).isGreaterThan(0.0)
    }

    @Test
    fun a_synced_recording_replaces_the_estimate_on_recompute() = runTest {
        service.doneToday(videoWorkoutId(), 20, VideoEffort.MODERATE)
        val estimateId = planRepo.getSessions(today, today).single().linkedActivityId!!
        val recordingId = (activityRepo.upsert(recording()) as Outcome.Ok).value

        recompute.recompute(today)

        val session = planRepo.getSessions(today, today).single()
        assertThat(session.linkedActivityId).isEqualTo(recordingId)
        assertThat(session.status).isEqualTo(PlannedStatus.COMPLETED)
        assertThat(activityRepo.getById(estimateId)).isNull()
        assertThat(activityRepo.observeRange(today, today).first().map { it.id }).containsExactly(recordingId)
    }

    @Test
    fun marking_done_with_a_linked_recording_creates_no_estimate() = runTest {
        val workoutId = videoWorkoutId()
        val recordingId = (activityRepo.upsert(recording()) as Outcome.Ok).value
        service.linkWorkout(recordingId, workoutId)
        val session = planRepo.getSessions(today, today).single()
        assertThat(session.linkedActivityId).isEqualTo(recordingId)
        assertThat(session.status).isEqualTo(PlannedStatus.COMPLETED)

        service.markDone(session.id, 20, VideoEffort.EASY)
        assertThat(activityRepo.observeRange(today, today).first()).hasSize(1)
    }

    @Test
    fun releasing_removes_only_an_estimate() = runTest {
        service.doneToday(videoWorkoutId(), 20, VideoEffort.MODERATE)
        val session = planRepo.getSessions(today, today).single()
        service.releaseEstimate(session.id)
        assertThat(planRepo.getSession(session.id)!!.linkedActivityId).isNull()
        assertThat(activityRepo.observeRange(today, today).first()).isEmpty()

        val recordingId = (activityRepo.upsert(recording()) as Outcome.Ok).value
        planRepo.linkActivity(session.id, recordingId)
        service.releaseEstimate(session.id)
        assertThat(planRepo.getSession(session.id)!!.linkedActivityId).isEqualTo(recordingId)
        assertThat(activityRepo.getById(recordingId)).isNotNull()
    }

    private fun recording(): ActivitySession {
        val start = today * 86_400_000L + 7 * 3_600_000L
        return ActivitySession(
            id = 0L, startAtMillis = start, endAtMillis = start + 1_500_000L, day = today,
            sportType = SportType.STRENGTH, sportGroup = SportType.STRENGTH.group, title = null,
            durationSec = 1500, elapsedSec = 1500, distanceMeters = null, activeEnergyKcal = null,
            totalEnergyKcal = null, avgHr = 128, maxHr = 160, avgSpeedMps = null, maxSpeedMps = null,
            avgCadenceSpm = null, elevationGainM = null, trimp = null, loadMethod = null, rpe = null,
            note = null, primarySource = ActivitySource.HEALTH_CONNECT,
            mergedSources = listOf(ActivitySource.HEALTH_CONNECT), dedupeBucket = "STRENGTH|0",
            userEditedFields = emptyList(), hasStreams = false, streams = null, laps = emptyList(),
            createdAtMillis = 0L, updatedAtMillis = 0L,
        )
    }
}
