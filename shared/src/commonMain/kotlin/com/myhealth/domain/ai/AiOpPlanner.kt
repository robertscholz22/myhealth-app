package com.myhealth.domain.ai

import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.engine.strength.UserExerciseIds
import com.myhealth.domain.engine.suggest.SessionCatalog
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.model.UserExercise
import kotlinx.datetime.LocalDate
import kotlin.random.Random

/** What the phone knows when it checks an op. */
data class AiLocalState(
    /** Epoch day of today on the phone. */
    val today: Long,
    val library: ExerciseLibrary,
    val workouts: Map<Long, StrengthWorkout>,
    val sessions: Map<Long, PlannedSession>,
    val links: List<AiLink>,
    val equipment: Set<Equipment>,
) {
    private val byAiId = links.associateBy { it.aiId }
    private val aiKeys = links.mapTo(mutableSetOf()) { it.kind to it.localKey }

    fun link(aiId: String): AiLink? = byAiId[aiId]

    /** Whether the AI created this row (and therefore may change it). */
    fun createdByAi(kind: AiKind, localKey: String): Boolean = (kind to localKey) in aiKeys
}

/** The one change an op makes, carried out by the sync service; `before` values make Undo possible. */
sealed interface AiStep {
    data class InsertExercise(val aiId: String, val exercise: UserExercise) : AiStep
    data class PutEquipment(val before: Set<Equipment>, val after: Set<Equipment>) : AiStep
    /** [workout] has id 0 and rows without ids. */
    data class InsertWorkout(val aiId: String, val workout: StrengthWorkout) : AiStep
    data class ReplaceWorkout(val before: StrengthWorkout, val after: StrengthWorkout) : AiStep
    data class RemoveWorkout(val before: StrengthWorkout) : AiStep
    /** [session] has id 0. */
    data class InsertSession(val aiId: String, val session: PlannedSession) : AiStep
    data class ReplaceSession(val before: PlannedSession, val after: PlannedSession) : AiStep
    data class RemoveSession(val before: PlannedSession) : AiStep
}

sealed interface AiDecision {
    data class Apply(val step: AiStep) : AiDecision
    data class Reject(val reason: String) : AiDecision
    /** The op's `ai-…` id already has a row: it was applied before and only the ack got lost. */
    data object AlreadyApplied : AiDecision
}

/**
 * P28: the AI's write scope, decided on the phone (owner's choice "add + replace unlocked"):
 * - it may create exercises, workouts and planned sessions, and set my equipment;
 * - it may change or delete the workouts it created;
 * - it may change or delete planned sessions that are still planned, not locked, not linked to an
 *   activity and not in the past — its own and the others;
 * - it never changes my own exercises, my workouts, locked or done sessions or calendar events.
 */
object AiOpPlanner {

    const val MAX_NAME = 60
    const val MAX_ROWS = 40
    const val MAX_SETS = 20
    const val MAX_REPS = 100
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 3600
    const val MAX_LOAD_KG = 500.0
    const val MAX_REST_SEC = 900
    const val MAX_DURATION_MIN = 600
    const val MAX_DISTANCE_KM = 300.0
    const val MIN_PACE_SEC = 120
    const val MAX_PACE_SEC = 900
    const val MAX_TEXT = 1000
    const val MAX_DAYS_AHEAD = 366L

    fun plan(op: AiOp, state: AiLocalState, now: Long, random: Random = Random.Default): AiDecision = when (op) {
        is AiOp.CreateExercise -> createExercise(op, state, now, random)
        is AiOp.SetEquipment -> setEquipment(op, state)
        is AiOp.CreateWorkout -> createWorkout(op, state, now)
        is AiOp.UpdateWorkout -> updateWorkout(op, state, now)
        is AiOp.DeleteWorkout -> deleteWorkout(op, state)
        is AiOp.ScheduleSession -> scheduleSession(op, state, now)
        is AiOp.UpdateSession -> updateSession(op, state, now)
        is AiOp.DeleteSession -> deleteSession(op, state)
    }

    // --- exercises and equipment -------------------------------------------------------------------

    private fun createExercise(op: AiOp.CreateExercise, state: AiLocalState, now: Long, random: Random): AiDecision {
        newAiId(op.aiId, state)?.let { return it }
        val name = op.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME) return reject("The exercise needs a name of up to $MAX_NAME characters.")
        val primary = muscles(op.primary) ?: return reject("Unknown muscle in ${op.primary}. Use: ${MuscleGroup.entries.joinToString()}.")
        if (primary.isEmpty()) return reject("Give at least one primary muscle.")
        val secondary = (muscles(op.secondary) ?: return reject("Unknown muscle in ${op.secondary}.")) - primary
        val equipment = enumOf<Equipment>(op.equipment) ?: return reject("Unknown equipment ${op.equipment}. Use: ${Equipment.entries.joinToString()}.")
        val pattern = enumOf<MovementPattern>(op.pattern) ?: return reject("Unknown movement pattern ${op.pattern}.")
        state.library.all.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
            return reject("An exercise named \"${it.name}\" exists already (id ${it.id}); use it instead.")
        }
        val exercise = Exercise(
            id = UserExerciseIds.next(random), name = name, primary = primary, secondary = secondary,
            equipment = equipment, pattern = pattern, unilateral = op.oneSided, isTimed = op.timed, cue = "",
        )
        val notes = op.notes?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }
        return AiDecision.Apply(AiStep.InsertExercise(op.aiId, UserExercise(exercise, notes, false, now, now)))
    }

    private fun setEquipment(op: AiOp.SetEquipment, state: AiLocalState): AiDecision {
        val set = op.equipment.map { enumOf<Equipment>(it) ?: return reject("Unknown equipment $it. Use: ${Equipment.entries.joinToString()}.") }
            .toSet() + Equipment.BODYWEIGHT
        return AiDecision.Apply(AiStep.PutEquipment(state.equipment, set))
    }

    // --- workouts ----------------------------------------------------------------------------------

    private fun createWorkout(op: AiOp.CreateWorkout, state: AiLocalState, now: Long): AiDecision {
        newAiId(op.aiId, state)?.let { return it }
        val name = workoutName(op.name) ?: return reject("The workout needs a name of up to $MAX_NAME characters.")
        val kind = enumOf<StrengthWorkoutKind>(op.kind) ?: return reject("Unknown workout kind ${op.kind}. Use: ${StrengthWorkoutKind.entries.joinToString()}.")
        val rows = rows(op.rows, state, workoutId = 0L).getOrElse { return reject(it.message!!) }
        val workout = StrengthWorkout(
            id = 0L, name = name, kind = kind, notes = text(op.notes), exercises = rows,
            createdAtMillis = now, updatedAtMillis = now, useInSuggestions = false,
        )
        return AiDecision.Apply(AiStep.InsertWorkout(op.aiId, workout))
    }

    private fun updateWorkout(op: AiOp.UpdateWorkout, state: AiLocalState, now: Long): AiDecision {
        val before = aiWorkout(op.ref, state).getOrElse { return reject(it.message!!) }
        val name = op.name?.let { workoutName(it) ?: return reject("The workout needs a name of up to $MAX_NAME characters.") } ?: before.name
        val kind = op.kind?.let { enumOf<StrengthWorkoutKind>(it) ?: return reject("Unknown workout kind $it.") } ?: before.kind
        val rows = op.rows?.let { rows(it, state, before.id).getOrElse { e -> return reject(e.message!!) } } ?: before.exercises
        val after = before.copy(name = name, kind = kind, exercises = rows, notes = op.notes?.let(::text) ?: before.notes, updatedAtMillis = now)
        return AiDecision.Apply(AiStep.ReplaceWorkout(before, after))
    }

    private fun deleteWorkout(op: AiOp.DeleteWorkout, state: AiLocalState): AiDecision {
        val before = aiWorkout(op.ref, state).getOrElse { return reject(it.message!!) }
        val users = state.sessions.values.filter { it.workoutId == before.id }
        if (users.isNotEmpty()) {
            return reject("Sessions use this workout (${users.joinToString { AiIds.session(it.id) }}); delete or change them first.")
        }
        return AiDecision.Apply(AiStep.RemoveWorkout(before))
    }

    private fun aiWorkout(ref: String, state: AiLocalState): Result<StrengthWorkout> {
        val id = resolve(ref, AiKind.WORKOUT, state, AiIds::parseWorkout) ?: return fail("No workout $ref.")
        val workout = state.workouts[id] ?: return fail("No workout $ref.")
        if (!state.createdByAi(AiKind.WORKOUT, id.toString())) {
            return fail("\"${workout.name}\" is not a workout the AI created; create a new one instead.")
        }
        return Result.success(workout)
    }

    private fun rows(rows: List<AiWorkoutRow>, state: AiLocalState, workoutId: Long): Result<List<StrengthWorkoutExercise>> {
        if (rows.isEmpty() || rows.size > MAX_ROWS) return fail("A workout needs 1 to $MAX_ROWS exercises.")
        return Result.success(
            rows.mapIndexed { i, row ->
                val exercise = exercise(row.exerciseId, state) ?: return fail("Unknown exercise ${row.exerciseId}; list_exercises shows the ids.")
                if (row.sets !in 1..MAX_SETS) return fail("${exercise.name}: sets must be 1–$MAX_SETS.")
                when {
                    row.reps != null && row.seconds != null -> return fail("${exercise.name}: give reps or seconds, not both.")
                    row.reps == null && row.seconds == null -> return fail("${exercise.name}: give ${if (exercise.isTimed) "seconds" else "reps"}.")
                    row.reps != null && exercise.isTimed -> return fail("${exercise.name} is timed: give seconds.")
                    row.reps != null && row.reps !in 1..MAX_REPS -> return fail("${exercise.name}: reps must be 1–$MAX_REPS.")
                    row.seconds != null && row.seconds !in MIN_SECONDS..MAX_SECONDS -> return fail("${exercise.name}: seconds must be $MIN_SECONDS–$MAX_SECONDS.")
                }
                row.loadKg?.let { if (it < 0.0 || it > MAX_LOAD_KG) return fail("${exercise.name}: load must be 0–$MAX_LOAD_KG kg.") }
                row.restSec?.let { if (it !in 0..MAX_REST_SEC) return fail("${exercise.name}: rest must be 0–$MAX_REST_SEC s.") }
                StrengthWorkoutExercise(
                    id = 0L, workoutId = workoutId, orderIndex = i, exerciseId = exercise.id, sets = row.sets,
                    reps = row.reps, seconds = row.seconds, loadKg = row.loadKg,
                    isBodyweight = exercise.equipment == Equipment.BODYWEIGHT && row.loadKg == null,
                    restSec = row.restSec, note = text(row.note),
                )
            },
        )
    }

    /** A catalog or own (not archived) exercise, or one the AI created, by any id it may use. */
    private fun exercise(ref: String, state: AiLocalState): Exercise? {
        val id = if (AiIds.isAi(ref)) state.link(ref)?.takeIf { it.kind == AiKind.EXERCISE }?.localKey ?: return null else ref
        if (state.library.userExercise(id)?.archived == true) return null
        return state.library.byId(id)
    }

    // --- planned sessions --------------------------------------------------------------------------

    private fun scheduleSession(op: AiOp.ScheduleSession, state: AiLocalState, now: Long): AiDecision {
        newAiId(op.aiId, state)?.let { return it }
        val f = op.session
        if (f.day == null) return reject("A new session needs a day (YYYY-MM-DD).")
        if (f.sport == null && f.workout == null) return reject("A new session needs a sport.")
        val base = PlannedSession(
            id = 0L, planId = null, day = state.today, startMinuteOfDay = null, sportType = SportType.STRENGTH,
            sessionType = SessionType.STRENGTH_FULL, intensity = Intensity.MODERATE, targetDurationMin = null,
            targetDistanceMeters = null, targetPaceSecPerKm = null, estimatedTrimp = null, description = null,
            rationale = null, status = PlannedStatus.PLANNED, locked = false, linkedActivityId = null,
            sourceSuggestionId = null, createdAtMillis = now, updatedAtMillis = now,
        )
        val session = merge(base, f, state, now, isNew = true).getOrElse { return reject(it.message!!) }
        return AiDecision.Apply(AiStep.InsertSession(op.aiId, session))
    }

    private fun updateSession(op: AiOp.UpdateSession, state: AiLocalState, now: Long): AiDecision {
        val before = changeableSession(op.ref, state).getOrElse { return reject(it.message!!) }
        val after = merge(before, op.session, state, now, isNew = false).getOrElse { return reject(it.message!!) }
        return AiDecision.Apply(AiStep.ReplaceSession(before, after))
    }

    private fun deleteSession(op: AiOp.DeleteSession, state: AiLocalState): AiDecision {
        val before = changeableSession(op.ref, state).getOrElse { return reject(it.message!!) }
        return AiDecision.Apply(AiStep.RemoveSession(before))
    }

    private fun changeableSession(ref: String, state: AiLocalState): Result<PlannedSession> {
        val id = resolve(ref, AiKind.SESSION, state, AiIds::parseSession) ?: return fail("No planned session $ref.")
        val s = state.sessions[id] ?: return fail("No planned session $ref.")
        return when {
            s.locked -> fail("Session $ref is locked by the owner and stays as it is.")
            s.status != PlannedStatus.PLANNED || s.linkedActivityId != null -> fail("Session $ref is done or skipped and stays as it is.")
            s.day < state.today -> fail("Session $ref is in the past and stays as it is.")
            else -> Result.success(s)
        }
    }

    /** [base] with the fields the AI gave; type, intensity and load estimate follow sport and workout. */
    private fun merge(base: PlannedSession, f: AiSessionFields, state: AiLocalState, now: Long, isNew: Boolean): Result<PlannedSession> {
        val day = f.day?.let { parseDay(it) ?: return fail("Day $it is not a date (YYYY-MM-DD).") } ?: base.day
        if (day < state.today) return fail("Day ${f.day} is in the past.")
        if (day > state.today + MAX_DAYS_AHEAD) return fail("Plan at most a year ahead.")
        val start = when (f.start) {
            null -> base.startMinuteOfDay
            "" -> null
            else -> parseTime(f.start) ?: return fail("Start ${f.start} is not a time (HH:MM).")
        }
        val workoutId = when (f.workout) {
            null -> base.workoutId
            "", "none" -> null
            else -> {
                val id = resolve(f.workout, AiKind.WORKOUT, state, AiIds::parseWorkout) ?: return fail("No workout ${f.workout}.")
                state.workouts[id]?.id ?: return fail("No workout ${f.workout}.")
            }
        }
        val workout = workoutId?.let { state.workouts[it] }
        val sport = f.sport?.let { enumOf<SportType>(it)?.takeIf { s -> s in PLANNABLE } ?: return fail("Unknown sport $it. Use: ${PLANNABLE.joinToString()}.") }
            ?: if (isNew && workout != null) workoutSport(workout) else base.sportType
        val type = f.sessionType?.let { enumOf<SessionType>(it) ?: return fail("Unknown session type $it.") }
            ?: when {
                workout != null && f.workout != null -> workout.kind.sessionType
                !isNew && f.sport == null -> base.sessionType
                else -> typesFor(sport).first()
            }
        if (workout != null && (type !in WORKOUT_TYPES || typesFor(sport).none { it in WORKOUT_TYPES })) {
            return fail("A workout can only be planned as a strength or mobility session.")
        }
        if (type !in typesFor(sport)) return fail("Session type $type does not fit $sport; use one of ${typesFor(sport).joinToString()}.")
        val intensity = f.intensity?.let { enumOf<Intensity>(it) ?: return fail("Unknown intensity $it. Use: ${Intensity.entries.joinToString()}.") }
            ?: if (f.sessionType != null || f.sport != null || isNew) SessionCatalog.entryFor(type)?.intensity ?: base.intensity else base.intensity
        val duration = f.durationMin ?: base.targetDurationMin ?: workout?.estimatedMinutes
        if (duration != null && duration !in 1..MAX_DURATION_MIN) return fail("Duration must be 1–$MAX_DURATION_MIN minutes.")
        val distance = f.distanceKm?.let {
            if (it <= 0.0 || it > MAX_DISTANCE_KM) return fail("Distance must be more than 0 and at most $MAX_DISTANCE_KM km.")
            it * METERS_PER_KM
        } ?: base.targetDistanceMeters
        val pace = when (f.pace) {
            null -> base.targetPaceSecPerKm
            "" -> null
            else -> (parsePace(f.pace) ?: return fail("Pace ${f.pace} is not m:ss per km.")).also {
                if (it !in MIN_PACE_SEC..MAX_PACE_SEC) return fail("Pace must be between 2:00 and 15:00 per km.")
            }
        }
        val trimp = duration?.let { SessionCatalog.entryFor(type)?.estTrimpFor(it, intensity) }
        return Result.success(
            base.copy(
                day = day, startMinuteOfDay = start, sportType = sport, sessionType = type, intensity = intensity,
                targetDurationMin = duration, targetDistanceMeters = distance, targetPaceSecPerKm = pace,
                estimatedTrimp = trimp, workoutId = workoutId,
                description = f.description?.let(::text) ?: base.description,
                rationale = f.rationale?.let(::text) ?: base.rationale,
                updatedAtMillis = now,
            ),
        )
    }

    private fun workoutSport(workout: StrengthWorkout): SportType =
        if (workout.kind.isMobility) SportType.MOBILITY else SportType.STRENGTH

    /** The session types the editor offers for [sport] ([com.myhealth.ui.training.sessionTypesFor]). */
    fun typesFor(sport: SportType): List<SessionType> =
        SessionCatalog.ALL.filter { it.sportType.group == sport.group }.map { it.sessionType }.ifEmpty { listOf(SessionType.CROSS_TRAINING) }

    /** The sports a session can be planned for (the editor's list). */
    val PLANNABLE: List<SportType> = listOf(
        SportType.RUN_OUTDOOR, SportType.RUN_TREADMILL, SportType.RUN_TRAIL, SportType.STRENGTH,
        SportType.CYCLING, SportType.CYCLING_INDOOR, SportType.SOCCER_TRAINING, SportType.SOCCER_MATCH,
        SportType.MOBILITY, SportType.SWIM, SportType.WALK, SportType.OTHER,
    )

    private val WORKOUT_TYPES = setOf(SessionType.STRENGTH_FULL, SessionType.STRENGTH_UPPER, SessionType.STRENGTH_LOWER, SessionType.MOBILITY)

    // --- helpers -----------------------------------------------------------------------------------

    /** Null when [aiId] can be used for a new row; otherwise the decision to return. */
    private fun newAiId(aiId: String, state: AiLocalState): AiDecision? = when {
        !AiIds.isAi(aiId) || aiId.length > 64 -> reject("New rows need an id starting with \"$AI_ID_PREFIX\".")
        state.link(aiId) != null -> AiDecision.AlreadyApplied
        else -> null
    }

    private fun resolve(ref: String, kind: AiKind, state: AiLocalState, parse: (String) -> Long?): Long? =
        if (AiIds.isAi(ref)) state.link(ref)?.takeIf { it.kind == kind }?.localKey?.toLongOrNull() else parse(ref)

    private fun muscles(names: List<String>): Set<MuscleGroup>? =
        names.map { enumOf<MuscleGroup>(it) ?: return null }.toSet()

    private inline fun <reified E : Enum<E>> enumOf(name: String): E? {
        val key = name.trim().uppercase().replace(' ', '_').replace('-', '_')
        return enumValues<E>().firstOrNull { it.name == key }
    }

    private fun workoutName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME }

    private fun text(raw: String?): String? = raw?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }

    fun parseDay(iso: String): Long? = runCatching { LocalDate.parse(iso.trim()).toEpochDays() }.getOrNull()

    fun parseTime(hhmm: String): Int? {
        val m = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(hhmm.trim()) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    fun parsePace(mss: String): Int? {
        val m = Regex("^(\\d{1,2}):([0-5]\\d)$").matchEntire(mss.trim()) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    private fun reject(reason: String) = AiDecision.Reject(reason)

    private fun <T> fail(reason: String): Result<T> = Result.failure(IllegalArgumentException(reason))

    private const val METERS_PER_KM = 1000.0
}
