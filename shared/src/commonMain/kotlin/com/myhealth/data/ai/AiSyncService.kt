package com.myhealth.data.ai

import com.myhealth.data.db.dao.AiDao
import com.myhealth.data.db.entity.AiChangeEntity
import com.myhealth.data.db.entity.AiConfigEntity
import com.myhealth.data.db.entity.AiLinkEntity
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.ai.AiBodyDto
import com.myhealth.domain.ai.AiConnection
import com.myhealth.domain.ai.AiDataGroup
import com.myhealth.domain.ai.AiDecision
import com.myhealth.domain.ai.AiKind
import com.myhealth.domain.ai.AiLink
import com.myhealth.domain.ai.AiLocalState
import com.myhealth.domain.ai.AiOp
import com.myhealth.domain.ai.AiOpPlanner
import com.myhealth.domain.ai.AiSetup
import com.myhealth.domain.ai.AiSnapshot
import com.myhealth.domain.ai.AiSnapshotBuilder
import com.myhealth.domain.ai.AiSnapshotInput
import com.myhealth.domain.ai.AiStep
import com.myhealth.domain.engine.load.HrBounds
import com.myhealth.domain.engine.strength.EquipmentSetCodec
import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.AiChange
import com.myhealth.domain.repository.AiChangeResult
import com.myhealth.domain.repository.AiPairingCode
import com.myhealth.domain.repository.AiPlanningRepository
import com.myhealth.domain.repository.AiRelayStatus
import com.myhealth.domain.repository.AiSyncResult
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.CycleRepository
import com.myhealth.domain.repository.GoalRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.LoadRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.repository.UserExerciseRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/** The content repositories the AI reads from and writes through. */
class AiContent(
    val exercises: UserExerciseRepository,
    val workouts: StrengthRepository,
    val plan: PlanRepository,
    val profile: ProfileRepository,
    val goals: GoalRepository,
    val calendar: CalendarRepository,
    val activities: ActivityRepository,
    val load: LoadRepository,
    val body: BodyRepository,
    val health: HealthRepository,
    val cycle: CycleRepository,
)

/**
 * P28: the phone's side of AI planning. A sync pulls the AI's ops from the relay, decides each one
 * with [AiOpPlanner] against the current local state, applies it through the repositories, logs it
 * (with what it replaced, for Undo), acks the results and pushes a fresh snapshot.
 */
class AiSyncService(
    private val dao: AiDao,
    private val content: AiContent,
    private val clock: PlatformClock,
    private val relay: RelayClient = RelayClient(),
) : AiPlanningRepository {

    private val mutex = Mutex()

    override val setup: Flow<AiSetup?> = dao.observeConfig().map { it?.toSetup() }

    override val changes: Flow<List<AiChange>> = dao.observeChanges(CHANGE_LOG_LIMIT).map { rows ->
        rows.map { r ->
            AiChange(
                id = r.id, batchId = r.batchId, opId = r.opId, summary = r.summary,
                result = AiChangeResult.valueOf(r.result), reason = r.reason, appliedAtMillis = r.appliedAtMillis,
            )
        }
    }

    override val createdByAi: Flow<Set<String>> =
        dao.observeLinks().map { links -> links.mapTo(mutableSetOf()) { "${it.kind}:${it.localKey}" } }

    // --- connection -------------------------------------------------------------------------------

    override suspend fun connect(connection: AiConnection, groups: Set<AiDataGroup>): Outcome<Unit> = mutex.withLock {
        val url = connection.relayUrl.trimEnd('/')
        when (val r = relay.register(url, connection.registrationKey)) {
            is Outcome.Err -> return@withLock Outcome.Err(r.error)
            is Outcome.Ok -> dao.upsertConfig(
                AiConfigEntity(
                    relayUrl = url, spaceId = r.value.spaceId, spaceToken = r.value.spaceToken,
                    groups = (groups + AiDataGroup.BASICS).joinToString(",") { it.name }, connectedAtMillis = clock.millis(),
                ),
            )
        }
        syncLocked()
        Outcome.Ok(Unit)
    }

    override suspend fun setGroups(groups: Set<AiDataGroup>): Outcome<Unit> = mutex.withLock {
        val config = dao.getConfig() ?: return@withLock notConnected()
        dao.upsertConfig(config.copy(groups = (groups + AiDataGroup.BASICS).joinToString(",") { it.name }))
        pushSnapshot(dao.getConfig()!!)
    }

    override suspend fun disconnect(): Outcome<Unit> = mutex.withLock {
        val config = dao.getConfig() ?: return@withLock Outcome.Ok(Unit)
        // Best effort: an offline phone still disconnects; the relay space then just goes stale.
        relay.deleteSpace(config.relayUrl, config.spaceToken)
        dao.deleteConfig()
        dao.deleteLinks()
        dao.deleteChanges()
        Outcome.Ok(Unit)
    }

    override suspend fun pairingCode(): Outcome<AiPairingCode> {
        val config = dao.getConfig() ?: return notConnected()
        return when (val r = relay.pairing(config.relayUrl, config.spaceToken)) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok(AiPairingCode(r.value.code, clock.millis() + r.value.expiresInSec * 1000L))
        }
    }

    override suspend fun relayStatus(): Outcome<AiRelayStatus> {
        val config = dao.getConfig() ?: return notConnected()
        return when (val r = relay.status(config.relayUrl, config.spaceToken)) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok(AiRelayStatus(r.value.pending, r.value.connectors, r.value.snapshotAt))
        }
    }

    // --- sync -------------------------------------------------------------------------------------

    override suspend fun sync(): Outcome<AiSyncResult> = mutex.withLock { syncLocked() }

    private suspend fun syncLocked(): Outcome<AiSyncResult> {
        val config = dao.getConfig() ?: return notConnected()
        val pulled = when (val r = relay.pullOps(config.relayUrl, config.spaceToken)) {
            is Outcome.Err -> return failed(config, r.error)
            is Outcome.Ok -> r.value
        }
        val now = clock.millis()
        val batchId = "b$now"
        var state = localState()
        val acks = mutableListOf<RelayClient.AckResult>()
        var applied = 0
        var rejected = 0
        for (p in pulled) {
            val earlier = dao.getChange(p.opId)
            if (earlier != null) {
                // Applied or rejected before, but the ack got lost: report the same result again.
                acks += ack(p.opId, earlier.result != AiChangeResult.REJECTED.name, earlier.reason)
                continue
            }
            val op = AiOp.decode(p.op)
            if (op == null) {
                val reason = "This change needs a newer MyHealth version."
                log(batchId, p.opId, "unknown", AiChangeResult.REJECTED, "Unknown change", reason, now)
                acks += ack(p.opId, false, reason)
                rejected++
                continue
            }
            when (val d = AiOpPlanner.plan(op, state, now)) {
                is AiDecision.Reject -> {
                    log(batchId, op.opId, op.typeName(), AiChangeResult.REJECTED, rejectedSummary(op), d.reason, now)
                    acks += ack(op.opId, false, d.reason)
                    rejected++
                }
                AiDecision.AlreadyApplied -> acks += ack(op.opId, true, null)
                is AiDecision.Apply -> when (val r = apply(d.step, state, now)) {
                    is Outcome.Err -> {
                        val reason = "The phone could not save this change (${r.error.describe()})."
                        log(batchId, op.opId, op.typeName(), AiChangeResult.REJECTED, rejectedSummary(op), reason, now)
                        acks += ack(op.opId, false, reason)
                        rejected++
                    }
                    is Outcome.Ok -> {
                        val done = r.value
                        state = done.state
                        dao.insertChange(
                            AiChangeEntity(
                                batchId = batchId, opId = op.opId, type = op.typeName(), result = AiChangeResult.APPLIED.name,
                                summary = done.summary, kind = done.undo.kind, localKey = done.undo.localKey,
                                beforeJson = AiUndo.encode(done.undo), appliedAtMillis = now,
                            ),
                        )
                        acks += ack(op.opId, true, null)
                        applied++
                    }
                }
            }
        }
        relay.ack(config.relayUrl, config.spaceToken, acks).let { if (it is Outcome.Err) return failed(config, it.error) }
        pushSnapshot(config).let { if (it is Outcome.Err) return it }
        return Outcome.Ok(AiSyncResult(applied, rejected, batchId.takeIf { applied + rejected > 0 }))
    }

    private suspend fun pushSnapshot(config: AiConfigEntity): Outcome<Unit> {
        val snapshot = buildSnapshot(config)
        val json = AiSnapshot.JSON.encodeToString(AiSnapshot.serializer(), snapshot)
        return when (val r = relay.putSnapshot(config.relayUrl, config.spaceToken, json)) {
            is Outcome.Err -> failed(config, r.error)
            is Outcome.Ok -> {
                dao.upsertConfig(config.copy(lastSyncAtMillis = clock.millis(), lastError = null))
                Outcome.Ok(Unit)
            }
        }
    }

    private suspend fun failed(config: AiConfigEntity, error: AppError): Outcome.Err {
        val text = if (error is AppError.Network && error.code == 401) {
            "The relay no longer knows this phone. Disconnect, then connect again."
        } else {
            error.describe()
        }
        dao.getConfig()?.let { dao.upsertConfig(it.copy(lastError = text)) } ?: Unit
        return Outcome.Err(error)
    }

    // --- applying ---------------------------------------------------------------------------------

    private class Applied(val state: AiLocalState, val summary: String, val undo: AiUndo)

    private suspend fun apply(step: AiStep, state: AiLocalState, now: Long): Outcome<Applied> {
        fun linked(aiId: String, kind: AiKind, key: String) = state.copy(links = state.links + AiLink(aiId, kind, key, now))
        return when (step) {
            is AiStep.InsertExercise -> content.exercises.upsert(step.exercise).then {
                val id = step.exercise.exercise.id
                link(step.aiId, AiKind.EXERCISE, id, now)
                Applied(
                    linked(step.aiId, AiKind.EXERCISE, id).copy(library = ExerciseLibrary(state.library.userExercises + step.exercise)),
                    "New exercise: ${step.exercise.exercise.name}", AiUndo.Created(AiKind.EXERCISE.name, id),
                )
            }
            is AiStep.PutEquipment -> {
                val profile = content.profile.getProfile()
                    ?: return Outcome.Err(AppError.Validation("profile", "No profile yet"))
                val all = step.after.containsAll(Equipment.entries)
                content.profile.upsert(profile.copy(availableEquipmentJson = if (all) null else EquipmentSetCodec.encode(step.after))).then {
                    Applied(
                        state.copy(equipment = step.after),
                        "My equipment: ${step.after.sortedBy { it.ordinal }.joinToString { it.label() }}",
                        AiUndo.EquipmentBefore(profile.availableEquipmentJson),
                    )
                }
            }
            is AiStep.InsertWorkout -> content.workouts.upsertWorkout(step.workout).then { id ->
                link(step.aiId, AiKind.WORKOUT, id.toString(), now)
                val saved = content.workouts.getById(id) ?: step.workout.copy(id = id)
                Applied(
                    linked(step.aiId, AiKind.WORKOUT, id.toString()).copy(workouts = state.workouts + (id to saved)),
                    "New workout: ${step.workout.name}", AiUndo.Created(AiKind.WORKOUT.name, id.toString()),
                )
            }
            is AiStep.ReplaceWorkout -> content.workouts.upsertWorkout(step.after).then {
                Applied(
                    state.copy(workouts = state.workouts + (step.after.id to step.after)),
                    "Changed workout: ${step.after.name}", AiUndo.WorkoutBefore(WorkoutJson.of(step.before)),
                )
            }
            is AiStep.RemoveWorkout -> content.workouts.deleteWorkout(step.before.id).then {
                Applied(
                    state.copy(workouts = state.workouts - step.before.id),
                    "Deleted workout: ${step.before.name}", AiUndo.WorkoutBefore(WorkoutJson.of(step.before)),
                )
            }
            is AiStep.InsertSession -> content.plan.upsertSession(step.session).then { id ->
                link(step.aiId, AiKind.SESSION, id.toString(), now)
                Applied(
                    linked(step.aiId, AiKind.SESSION, id.toString()).copy(sessions = state.sessions + (id to step.session.copy(id = id))),
                    "Planned ${step.session.label()}", AiUndo.Created(AiKind.SESSION.name, id.toString()),
                )
            }
            is AiStep.ReplaceSession -> content.plan.upsertSession(step.after).then {
                Applied(
                    state.copy(sessions = state.sessions + (step.after.id to step.after)),
                    "Changed ${step.after.label()}", AiUndo.SessionBefore(SessionJson.of(step.before)),
                )
            }
            is AiStep.RemoveSession -> content.plan.deleteSession(step.before.id).then {
                Applied(
                    state.copy(sessions = state.sessions - step.before.id),
                    "Deleted ${step.before.label()}", AiUndo.SessionBefore(SessionJson.of(step.before)),
                )
            }
        }
    }

    private suspend fun link(aiId: String, kind: AiKind, key: String, now: Long) =
        dao.insertLink(AiLinkEntity(aiId = aiId, kind = kind.name, localKey = key, createdAtMillis = now))

    // --- undo -------------------------------------------------------------------------------------

    override suspend fun undo(batchId: String): Outcome<Int> = mutex.withLock {
        val config = dao.getConfig() ?: return@withLock notConnected()
        var undone = 0
        for (change in dao.getBatch(batchId).sortedByDescending { it.id }) {
            if (change.result != AiChangeResult.APPLIED.name) continue
            val undo = change.beforeJson?.let(AiUndo::decode) ?: continue
            if (revert(undo)) {
                dao.setResult(change.id, AiChangeResult.UNDONE.name)
                undone++
            }
        }
        // The relay should see the reverted state; a failed push is retried by the next sync.
        if (undone > 0) pushSnapshot(dao.getConfig() ?: config)
        Outcome.Ok(undone)
    }

    /** False when the row has moved on (e.g. the session was done meanwhile) and stays as it is. */
    private suspend fun revert(undo: AiUndo): Boolean = when (undo) {
        is AiUndo.Created -> when (AiKind.valueOf(undo.kind)) {
            AiKind.EXERCISE -> {
                val ok = if (content.exercises.usageCount(undo.localKey) > 0) {
                    content.exercises.setArchived(undo.localKey, true)
                } else {
                    content.exercises.delete(undo.localKey)
                }
                (ok is Outcome.Ok).also { if (it) dao.deleteLink(AiKind.EXERCISE.name, undo.localKey) }
            }
            AiKind.WORKOUT -> (content.workouts.deleteWorkout(undo.localKey.toLong()) is Outcome.Ok)
                .also { if (it) dao.deleteLink(AiKind.WORKOUT.name, undo.localKey) }
            AiKind.SESSION -> {
                val current = content.plan.getSession(undo.localKey.toLong())
                when {
                    current == null -> true.also { dao.deleteLink(AiKind.SESSION.name, undo.localKey) }
                    !current.isOpen() -> false
                    else -> (content.plan.deleteSession(current.id) is Outcome.Ok)
                        .also { if (it) dao.deleteLink(AiKind.SESSION.name, undo.localKey) }
                }
            }
        }
        is AiUndo.EquipmentBefore -> {
            val profile = content.profile.getProfile()
            profile != null && content.profile.upsert(profile.copy(availableEquipmentJson = undo.json)) is Outcome.Ok
        }
        is AiUndo.WorkoutBefore -> content.workouts.upsertWorkout(undo.workout.toModel()) is Outcome.Ok
        is AiUndo.SessionBefore -> {
            val current = content.plan.getSession(undo.session.id)
            if (current != null && !current.isOpen()) false else content.plan.upsertSession(undo.session.toModel()) is Outcome.Ok
        }
    }

    private fun PlannedSession.isOpen() = status == PlannedStatus.PLANNED && linkedActivityId == null

    // --- state and snapshot -----------------------------------------------------------------------

    private suspend fun localState(): AiLocalState {
        val today = clock.todayEpochDay()
        val profile = content.profile.getProfile()
        return AiLocalState(
            today = today,
            library = content.exercises.getLibrary(),
            workouts = content.workouts.observeAll().first().associateBy { it.id },
            sessions = content.plan.getSessions(today - AiSnapshotBuilder.DAYS_BACK, today + AiOpPlanner.MAX_DAYS_AHEAD).associateBy { it.id },
            links = dao.getLinks().map { it.toLink() },
            equipment = EquipmentSetCodec.decode(profile?.availableEquipmentJson) ?: Equipment.entries.toSet(),
        )
    }

    private suspend fun buildSnapshot(config: AiConfigEntity): AiSnapshot {
        val groups = AiDataGroup.parse(config.groups.split(','))
        val today = clock.todayEpochDay()
        val from = today - AiSnapshotBuilder.DAYS_BACK
        val to = today + AiSnapshotBuilder.DAYS_AHEAD
        val profile = content.profile.getProfile()
        val library = content.exercises.getLibrary()
        val sessions = content.plan.getSessions(from, to)
        val events = content.calendar.observeOccurrences(from, to).first()
        val activities = content.activities.observeRange(from, today).first()
        val loads = content.activities.getRange(from, today).mapNotNull { a -> a.trimp?.let { a.id to it } }.toMap()
        val sports = (activities.map { it.sportType } + sessions.map { it.sportType } + events.mapNotNull { it.sportType })
            .map { it.name }.distinct().sorted()
        return AiSnapshotBuilder.build(
            AiSnapshotInput(
                nowMillis = clock.millis(),
                today = today,
                groups = groups,
                exercises = library.all,
                ownExerciseIds = library.custom.mapTo(mutableSetOf()) { it.id },
                equipment = EquipmentSetCodec.decode(profile?.availableEquipmentJson) ?: Equipment.entries.toSet(),
                sports = sports,
                workouts = content.workouts.observeAll().first(),
                goals = content.goals.observeAll().first(),
                sessions = sessions,
                events = events,
                activities = activities,
                activityLoad = loads,
                links = dao.getLinks().map { it.toLink() },
                dailyLoads = if (AiDataGroup.LOAD in groups) content.load.getRange(from, today) else emptyList(),
                progress = if (AiDataGroup.LOAD in groups) content.workouts.getAllProgress() else emptyList(),
                body = if (AiDataGroup.BODY in groups && profile != null) body(profile, today) else null,
                cycle = if (AiDataGroup.CYCLE in groups && content.cycle.isTrackingEnabled().first()) content.cycle.statusFor(today) else null,
                predictedCycles = if (AiDataGroup.CYCLE in groups && content.cycle.isTrackingEnabled().first()) {
                    content.cycle.observeForecast(today).first().cycles
                } else {
                    emptyList()
                },
            ),
        )
    }

    private suspend fun body(profile: com.myhealth.domain.model.Profile, today: Long): AiBodyDto {
        val date = LocalDate.fromEpochDays(today.toInt())
        val restingRecent = content.health.observeRange(today - 7, today).first().mapNotNull { it.restingHr }
        val sleep = content.health.observeSleepRange(today - 7, today).first().map { it.totalSleepMin }
        val latestLoad = content.load.getLatest()
        val bounds = HrBounds.compute(profile, date, restingRecent)
        return AiBodyDto(
            age = profile.ageYears(date),
            sex = profile.sex.name,
            weightKg = (content.body.latestWeight(WEIGHT_MAX_AGE_DAYS)?.weightKg ?: profile.fallbackWeightKg)?.let { (it * 10).roundToInt() / 10.0 },
            restingHr = profile.restingHrManual ?: restingRecent.lastOrNull(),
            maxHr = bounds.hrMax,
            sleepHoursAvg7d = sleep.takeIf { it.isNotEmpty() }?.let { (it.average() / 6.0).roundToInt() / 10.0 },
            recoveryToday = latestLoad?.takeIf { it.day == today }?.recoveryScore,
            recoveryBand = latestLoad?.takeIf { it.day == today }?.recoveryBand?.name,
        )
    }

    // --- helpers ----------------------------------------------------------------------------------

    private suspend fun log(batchId: String, opId: String, type: String, result: AiChangeResult, summary: String, reason: String?, now: Long) {
        dao.insertChange(
            AiChangeEntity(batchId = batchId, opId = opId, type = type, result = result.name, summary = summary, reason = reason, appliedAtMillis = now),
        )
    }

    private fun ack(opId: String, applied: Boolean, reason: String?) =
        RelayClient.AckResult(opId, if (applied) "applied" else "rejected", reason)

    private fun <T> notConnected(): Outcome<T> = Outcome.Err(AppError.Validation("ai", "AI planning is not connected."))

    private inline fun <A, B> Outcome<A>.then(f: (A) -> B): Outcome<B> = when (this) {
        is Outcome.Ok -> Outcome.Ok(f(value))
        is Outcome.Err -> this
    }

    companion object {
        const val CHANGE_LOG_LIMIT = 200
        private const val WEIGHT_MAX_AGE_DAYS = 60L
    }
}

private fun AiConfigEntity.toSetup() = AiSetup(
    relayUrl = relayUrl, spaceId = spaceId, spaceToken = spaceToken, groups = AiDataGroup.parse(groups.split(',')),
    lastSyncAtMillis = lastSyncAtMillis, connectedAtMillis = connectedAtMillis, lastError = lastError,
)

private fun AiLinkEntity.toLink() = AiLink(aiId, AiKind.valueOf(kind), localKey, createdAtMillis)

private fun AiOp.typeName(): String = when (this) {
    is AiOp.CreateExercise -> "create_exercise"
    is AiOp.SetEquipment -> "set_equipment"
    is AiOp.CreateWorkout -> "create_workout"
    is AiOp.UpdateWorkout -> "update_workout"
    is AiOp.DeleteWorkout -> "delete_workout"
    is AiOp.ScheduleSession -> "schedule_session"
    is AiOp.UpdateSession -> "update_session"
    is AiOp.DeleteSession -> "delete_session"
}

private fun rejectedSummary(op: AiOp): String = when (op) {
    is AiOp.CreateExercise -> "New exercise: ${op.name}"
    is AiOp.SetEquipment -> "My equipment"
    is AiOp.CreateWorkout -> "New workout: ${op.name}"
    is AiOp.UpdateWorkout -> "Change workout ${op.ref}"
    is AiOp.DeleteWorkout -> "Delete workout ${op.ref}"
    is AiOp.ScheduleSession -> "Plan a session${op.session.day?.let { " on $it" } ?: ""}"
    is AiOp.UpdateSession -> "Change session ${op.ref}"
    is AiOp.DeleteSession -> "Delete session ${op.ref}"
}

private fun PlannedSession.label(): String =
    "${sessionType.name.lowercase().replace('_', ' ')} on ${LocalDate.fromEpochDays(day.toInt())}"

private fun Equipment.label(): String = name.lowercase().replace('_', ' ')

private fun AppError.describe(): String = when (this) {
    is AppError.Network -> (cause as? RelayException)?.message ?: if (code != null) "HTTP $code" else "no connection"
    is AppError.Validation -> message
    is AppError.Parse -> detail
    is AppError.Storage -> "storage error"
    else -> toString()
}
