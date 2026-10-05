package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlanStatus
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.TrainingPlan
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [RoomPlanRepository] against an in-memory [FakePlanDao] (PLAN P3.2). The invariant under test
 * is §2.2.4's "at most one `ACTIVE` plan", which lives in the repository, not in the schema.
 */
class RoomPlanRepositoryTest {

    private val dao = FakePlanDao()
    private val clock = Fixtures.fixedClock("2026-09-12T12:00:00Z")
    private val repo = RoomPlanRepository(dao, clock, Dispatchers.Unconfined)

    private val monday = Fixtures.epochDay("2026-09-14")

    @Test
    fun activating_a_plan_archives_the_previously_active_one() = runTest {
        val first = repo.upsertPlan(plan(name = "Base block", status = PlanStatus.ACTIVE)).id()
        val second = repo.upsertPlan(plan(name = "Build block", status = PlanStatus.ACTIVE)).id()

        assertThat(repo.getPlan(first)?.status).isEqualTo(PlanStatus.ARCHIVED)
        assertThat(repo.getPlan(second)?.status).isEqualTo(PlanStatus.ACTIVE)
        assertThat(repo.observeActivePlan().first()?.id).isEqualTo(second)
        assertThat(repo.observeAllPlans().first()).hasSize(2)
    }

    @Test
    fun a_draft_plan_never_archives_the_active_one() = runTest {
        val active = repo.upsertPlan(plan(name = "Base block", status = PlanStatus.ACTIVE)).id()
        repo.upsertPlan(plan(name = "Next season", status = PlanStatus.DRAFT))

        assertThat(repo.observeActivePlan().first()?.id).isEqualTo(active)
        assertThat(repo.getPlan(active)?.status).isEqualTo(PlanStatus.ACTIVE)
    }

    @Test
    fun reactivating_the_same_plan_leaves_it_active() = runTest {
        val id = repo.upsertPlan(plan(name = "Base block", status = PlanStatus.ACTIVE)).id()
        val stored = repo.getPlan(id)!!

        repo.upsertPlan(stored.copy(notes = "edited"))

        assertThat(repo.getPlan(id)?.status).isEqualTo(PlanStatus.ACTIVE)
        assertThat(repo.getPlan(id)?.notes).isEqualTo("edited")
        assertThat(repo.observeActivePlan().first()?.id).isEqualTo(id)
    }

    @Test
    fun sessions_are_stamped_and_observed_by_range() = runTest {
        val id = repo.upsertSession(session(day = monday)).id()
        repo.upsertSessions(listOf(session(day = monday + 1), session(day = monday + 9)))

        val inWeek = repo.observeSessions(monday, monday + 6).first()
        assertThat(inWeek.map { it.day }).containsExactly(monday, monday + 1).inOrder()
        assertThat(repo.getSessions(monday, monday + 30)).hasSize(3)
        assertThat(repo.getSession(id)?.createdAtMillis).isEqualTo(clock.millis())
        assertThat(repo.getSession(id)?.updatedAtMillis).isEqualTo(clock.millis())
    }

    @Test
    fun status_lock_and_link_writes_only_touch_their_own_field() = runTest {
        val id = repo.upsertSession(session(day = monday)).id()

        repo.setSessionStatus(id, PlannedStatus.COMPLETED)
        repo.setSessionLocked(id, locked = true)
        repo.linkActivity(sessionId = id, activityId = 42L)

        val stored = repo.getSession(id)!!
        assertThat(stored.status).isEqualTo(PlannedStatus.COMPLETED)
        assertThat(stored.locked).isTrue()
        assertThat(stored.linkedActivityId).isEqualTo(42L)
        assertThat(stored.sportType).isEqualTo(SportType.RUN_OUTDOOR)
    }

    @Test
    fun replaceable_sessions_exclude_locked_finished_and_hand_planned_rows() = runTest {
        val open = repo.upsertSession(session(day = monday)).id()
        val locked = repo.upsertSession(session(day = monday + 1)).id()
        val done = repo.upsertSession(session(day = monday + 2)).id()
        // BUG-15: a hand-planned session (no source suggestion) is never replaceable either.
        repo.upsertSession(session(day = monday + 3, fromSuggestion = false))
        repo.setSessionLocked(locked, locked = true)
        repo.setSessionStatus(done, PlannedStatus.COMPLETED)

        val replaceable = repo.getReplaceableSessions(monday, monday + 6)

        assertThat(replaceable.map { it.id }).containsExactly(open)
        repo.deleteSession(open)
        assertThat(repo.getSession(open)).isNull()
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun Outcome<Long>.id(): Long = (this as Outcome.Ok<Long>).value

    private fun plan(name: String, status: PlanStatus) = TrainingPlan(
        id = 0,
        name = name,
        startDay = monday,
        endDay = monday + 56,
        status = status,
        primaryGoalId = null,
        notes = null,
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )

    private fun session(day: Long, fromSuggestion: Boolean = true) = PlannedSession(
        id = 0,
        planId = null,
        day = day,
        startMinuteOfDay = 7 * 60,
        sportType = SportType.RUN_OUTDOOR,
        sessionType = SessionType.EASY_RUN,
        intensity = Intensity.LOW,
        targetDurationMin = 45,
        targetDistanceMeters = 8_000.0,
        targetPaceSecPerKm = 330,
        estimatedTrimp = 60.0,
        description = null,
        rationale = null,
        status = PlannedStatus.PLANNED,
        locked = false,
        linkedActivityId = null,
        sourceSuggestionId = if (fromSuggestion) 1L else null,
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )
}
