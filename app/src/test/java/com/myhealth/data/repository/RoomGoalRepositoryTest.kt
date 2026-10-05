package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [RoomGoalRepository] against an in-memory [FakeGoalDao] (PLAN P6.1). The invariant under test is
 * "only one goal may have `priority = 1`", which lives in the repository, not in the schema.
 */
class RoomGoalRepositoryTest {

    private val dao = FakeGoalDao()
    private val clock = Fixtures.fixedClock("2026-09-14T12:00:00Z")
    private val repo = RoomGoalRepository(dao, clock, Dispatchers.Unconfined)

    private fun goal(
        title: String,
        priority: Int = 1,
        id: Long = 0L,
        type: GoalType = GoalType.RACE_TIME,
    ): Goal = Goal(
        id = id,
        type = type,
        title = title,
        targetDay = Fixtures.epochDay("2026-11-15"),
        targetDistanceMeters = 5000.0,
        targetTimeSec = 1200,
        targetWeightKg = null,
        targetValue = null,
        priority = priority,
        status = GoalStatus.ACTIVE,
        linkedEventId = null,
        notes = null,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    private fun Outcome<Long>.id(): Long = (this as Outcome.Ok).value

    @Test
    fun goal06_only_one_primary_goal() = runTest {
        val first = repo.upsert(goal("Sub-20 5k")).id()
        val second = repo.upsert(goal("Half marathon")).id()

        assertThat(repo.getById(first)?.priority).isEqualTo(2)
        assertThat(repo.getById(second)?.priority).isEqualTo(1)
        assertThat(repo.observePrimary().first()?.id).isEqualTo(second)

        // Promoting the first one demotes the second, still leaving exactly one primary.
        assertThat(repo.setPrimary(first)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(repo.getById(first)?.priority).isEqualTo(1)
        assertThat(repo.getById(second)?.priority).isEqualTo(2)
        assertThat(repo.observeAll().first().count { it.priority == 1 }).isEqualTo(1)

        // A non-primary upsert never touches the reigning primary.
        repo.upsert(goal("Body weight", priority = 2, type = GoalType.BODY_WEIGHT))
        assertThat(repo.observeAll().first().count { it.priority == 1 }).isEqualTo(1)
        assertThat(repo.observePrimary().first()?.id).isEqualTo(first)
    }

    @Test
    fun timestamps_are_stamped_on_create_and_preserved_on_edit() = runTest {
        val id = repo.upsert(goal("Sub-20 5k")).id()
        val created = repo.getById(id)!!
        assertThat(created.createdAtMillis).isEqualTo(clock.millis())

        val edited = repo.upsert(created.copy(title = "Sub-19 5k"))
        assertThat(edited).isInstanceOf(Outcome.Ok::class.java)
        assertThat(repo.getById(id)?.title).isEqualTo("Sub-19 5k")
        assertThat(repo.getById(id)?.createdAtMillis).isEqualTo(created.createdAtMillis)
    }

    @Test
    fun status_changes_and_deletes_are_observable() = runTest {
        val id = repo.upsert(goal("Sub-20 5k")).id()

        repo.setStatus(id, GoalStatus.ACHIEVED)
        assertThat(repo.getById(id)?.status).isEqualTo(GoalStatus.ACHIEVED)
        assertThat(repo.observeByStatus(GoalStatus.ACTIVE).first()).isEmpty()
        assertThat(repo.observeByStatus(GoalStatus.ACHIEVED).first()).hasSize(1)
        // An achieved goal is no longer the primary one the suggester reads.
        assertThat(repo.observePrimary().first()).isNull()

        repo.delete(id)
        assertThat(repo.getById(id)).isNull()
        assertThat(repo.observeAll().first()).isEmpty()
    }

    @Test
    fun a_zero_priority_is_normalised_to_the_primary_rank() = runTest {
        val id = repo.upsert(goal("Sub-20 5k", priority = 0)).id()
        assertThat(repo.getById(id)?.priority).isEqualTo(1)
    }
}
