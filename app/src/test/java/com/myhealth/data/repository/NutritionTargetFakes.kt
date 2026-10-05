package com.myhealth.data.repository

import com.myhealth.data.db.dao.NutritionDao
import com.myhealth.data.db.entity.NutritionTargetSnapshotEntity
import com.myhealth.data.db.entity.WaterLogEntity
import com.myhealth.domain.engine.calendar.LinkProposal
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventOverride
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.TrainingPlan
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * In-memory fakes for the collaborators of [RoomNutritionRepository] (PLAN P4.12). Only the reads
 * `ensureTarget` performs are implemented; everything else on these interfaces is stubbed, since a
 * fake that pretended to do more would be a second implementation to keep correct.
 */
internal class FakeNutritionDao : NutritionDao {

    val targets = MutableStateFlow<Map<Long, NutritionTargetSnapshotEntity>>(emptyMap())
    val water = MutableStateFlow<List<WaterLogEntity>>(emptyList())

    /** How many times a snapshot was written — the recompute counter of the P4.12 tests. */
    var writeCount: Int = 0
        private set

    override suspend fun upsertTarget(entity: NutritionTargetSnapshotEntity) {
        writeCount++
        targets.value = targets.value + (entity.day to entity)
    }

    override suspend fun upsertTargets(entities: List<NutritionTargetSnapshotEntity>) {
        entities.forEach { upsertTarget(it) }
    }

    override suspend fun getTarget(day: Long): NutritionTargetSnapshotEntity? = targets.value[day]

    override suspend fun getById(day: Long): NutritionTargetSnapshotEntity? = targets.value[day]

    override suspend fun deleteById(day: Long) {
        targets.value = targets.value - day
    }

    override fun observeTarget(day: Long): Flow<NutritionTargetSnapshotEntity?> =
        targets.map { it[day] }

    override fun observeTargets(
        fromDay: Long,
        toDay: Long,
    ): Flow<List<NutritionTargetSnapshotEntity>> =
        targets.map { all -> all.values.filter { it.day in fromDay..toDay }.sortedBy { it.day } }

    override suspend fun upsertWater(entity: WaterLogEntity): Long {
        val id = if (entity.id == 0L) water.value.size + 1L else entity.id
        water.value = water.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override fun observeWaterDay(day: Long): Flow<List<WaterLogEntity>> =
        water.map { rows -> rows.filter { it.day == day }.sortedBy { it.atMinuteOfDay ?: 0 } }

    override fun observeWaterTotal(day: Long): Flow<Int> =
        water.map { rows -> rows.filter { it.day == day }.sumOf { it.ml } }

    override suspend fun deleteWaterById(id: Long) {
        water.value = water.value.filterNot { it.id == id }
    }
}

internal class FakeTargetProfileRepository(profile: Profile? = null) : ProfileRepository {

    val profiles = MutableStateFlow(profile)

    override fun observeProfile(): Flow<Profile?> = profiles

    override suspend fun getProfile(): Profile? = profiles.value

    override suspend fun upsert(profile: Profile): Outcome<Unit> {
        profiles.value = profile
        return Outcome.Ok(Unit)
    }
}

internal class FakeTargetPlanRepository : PlanRepository {

    var sessions: List<PlannedSession> = emptyList()

    override fun observeActivePlan(): Flow<TrainingPlan?> = flowOf(null)
    override fun observeAllPlans(): Flow<List<TrainingPlan>> = flowOf(emptyList())
    override fun observeSessions(fromDay: Long, toDay: Long): Flow<List<PlannedSession>> =
        flowOf(sessions.filter { it.day in fromDay..toDay })

    override fun observeSessionsForPlan(planId: Long): Flow<List<PlannedSession>> = emptyFlow()
    override suspend fun getPlan(id: Long): TrainingPlan? = null
    override suspend fun getSession(id: Long): PlannedSession? = sessions.firstOrNull { it.id == id }

    override suspend fun getSessions(fromDay: Long, toDay: Long): List<PlannedSession> =
        sessions.filter { it.day in fromDay..toDay }

    override suspend fun getReplaceableSessions(fromDay: Long, toDay: Long): List<PlannedSession> =
        getSessions(fromDay, toDay)

    override suspend fun upsertPlan(plan: TrainingPlan): Outcome<Long> = Outcome.Ok(0L)
    override suspend fun deletePlan(id: Long): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun upsertSession(session: PlannedSession): Outcome<Long> = Outcome.Ok(0L)
    override suspend fun upsertSessions(sessions: List<PlannedSession>): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun setSessionStatus(id: Long, status: PlannedStatus): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun setSessionLocked(id: Long, locked: Boolean): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun linkActivity(sessionId: Long, activityId: Long?): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun deleteSession(id: Long): Outcome<Unit> = Outcome.Ok(Unit)
}

internal class FakeTargetCalendarRepository : CalendarRepository {

    var occurrences: List<EventOccurrence> = emptyList()

    override fun observeOccurrences(fromDay: Long, toDay: Long): Flow<List<EventOccurrence>> =
        flowOf(occurrences.filter { it.occurrenceDay in fromDay..toDay })

    override fun observeRange(fromDay: Long, toDay: Long): Flow<Map<Long, CalendarDay>> =
        flowOf(emptyMap())

    override fun observeDay(day: Long): Flow<CalendarDay> = flowOf(CalendarDay.empty(day))
    override fun observeKeyEvents(fromDay: Long): Flow<List<CalendarEvent>> = flowOf(emptyList())
    override fun observeLinkProposals(fromDay: Long, toDay: Long): Flow<List<LinkProposal>> =
        flowOf(emptyList())

    override suspend fun getEvent(id: Long): CalendarEvent? = null
    override suspend fun upsertEvent(event: CalendarEvent): Outcome<Long> = Outcome.Ok(0L)
    override suspend fun deleteEvent(id: Long): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun upsertOverride(override: EventOverride): Outcome<Long> = Outcome.Ok(0L)
    override suspend fun deleteOverride(id: Long): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun linkActivity(
        eventId: Long,
        activityId: Long?,
        method: LinkMethod?,
    ): Outcome<Unit> = Outcome.Ok(Unit)
}
