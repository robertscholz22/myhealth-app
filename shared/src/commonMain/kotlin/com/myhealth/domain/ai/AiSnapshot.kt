package com.myhealth.domain.ai

import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.PredictedCycle
import com.myhealth.domain.model.StrengthWorkout
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.round

/**
 * P28: what the phone shows the AI, pushed to the relay. Groups that are off leave their fields
 * null; the relay's tools say so instead of guessing. Days are ISO dates in the phone's time zone.
 */
@Serializable
data class AiSnapshot(
    val version: Int = VERSION,
    val generatedAtMillis: Long,
    val today: String,
    val groups: List<String>,
    val window: AiWindow,
    val sports: List<String>,
    val equipment: List<String>,
    val exercises: List<AiExerciseDto>,
    val workouts: List<AiWorkoutDto>,
    val goals: List<AiGoalDto>,
    val sessions: List<AiSessionDto>,
    val events: List<AiEventDto>,
    val activities: List<AiActivityDto>,
    /** `ai-…` id → local snapshot id (`USER_…`, `w12`, `s34`) for every row the AI created. */
    val links: Map<String, String>,
    val load: AiLoadDto? = null,
    val body: AiBodyDto? = null,
    val cycle: AiCycleDto? = null,
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val VERSION: Int = 1
        val JSON: Json = Json { explicitNulls = false; encodeDefaults = true }
    }
}

@Serializable
data class AiWindow(val from: String, val to: String)

@Serializable
data class AiExerciseDto(
    val id: String,
    val name: String,
    val primary: List<String>,
    val secondary: List<String>,
    val equipment: String,
    val pattern: String,
    val timed: Boolean,
    val oneSided: Boolean,
    /** `catalog`, `mine` (the owner's own), or `ai` (created by the AI). */
    val source: String,
)

@Serializable
data class AiWorkoutDto(
    val id: String,
    val name: String,
    val kind: String,
    val minutes: Int,
    val rows: List<AiWorkoutRow>,
    val video: Boolean,
    val byAi: Boolean,
    val notes: String? = null,
)

@Serializable
data class AiGoalDto(
    val title: String,
    val type: String,
    val day: String? = null,
    val distanceKm: Double? = null,
    val timeSec: Int? = null,
    val priority: Int,
)

@Serializable
data class AiSessionDto(
    val id: String,
    val day: String,
    val start: String? = null,
    val sport: String,
    val sessionType: String,
    val intensity: String,
    val durationMin: Int? = null,
    val distanceKm: Double? = null,
    val pace: String? = null,
    val loadAu: Double? = null,
    val workout: String? = null,
    val description: String? = null,
    val status: String,
    val locked: Boolean,
    /** Whether the AI may change or delete it (not locked, still planned, not in the past). */
    val changeable: Boolean,
    val byAi: Boolean,
)

@Serializable
data class AiEventDto(
    val day: String,
    val start: String? = null,
    val durationMin: Int? = null,
    val title: String,
    val type: String,
    val sport: String? = null,
    val intensity: String? = null,
    val key: Boolean,
)

@Serializable
data class AiActivityDto(
    val day: String,
    val sport: String,
    val durationMin: Int,
    val distanceKm: Double? = null,
    val loadAu: Double? = null,
    val avgHr: Int? = null,
)

@Serializable
data class AiLoadDto(
    val days: List<AiDayLoad>,
    val progress: List<AiProgressDto>,
)

@Serializable
data class AiDayLoad(
    val day: String,
    val loadAu: Double,
    val fitness: Double,
    val fatigue: Double,
    val acwr: Double? = null,
    val recovery: Int? = null,
)

@Serializable
data class AiProgressDto(
    val exerciseId: String,
    val loadKg: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val lastFeedback: String? = null,
)

@Serializable
data class AiBodyDto(
    val age: Int? = null,
    val sex: String? = null,
    val weightKg: Double? = null,
    val restingHr: Int? = null,
    val maxHr: Int? = null,
    val sleepHoursAvg7d: Double? = null,
    val recoveryToday: Int? = null,
    val recoveryBand: String? = null,
)

@Serializable
data class AiCycleDto(
    val phase: String,
    val dayOfCycle: Int,
    val nextPeriodStart: String,
    val predicted: Boolean,
    val periodsAhead: List<AiWindow>,
)

/** Everything the snapshot is built from; the sync service reads it from the repositories. */
data class AiSnapshotInput(
    val nowMillis: Long,
    val today: Long,
    val groups: Set<AiDataGroup>,
    val exercises: List<Exercise>,
    /** Ids of the owner's own (not archived) exercises; the rest of [exercises] is the catalog. */
    val ownExerciseIds: Set<String>,
    val equipment: Set<Equipment>,
    val sports: List<String>,
    val workouts: List<StrengthWorkout>,
    val goals: List<Goal>,
    val sessions: List<PlannedSession>,
    val events: List<CalendarEvent>,
    val activities: List<ActivitySummary>,
    /** Activity id → load (TRIMP / AU), when computed. */
    val activityLoad: Map<Long, Double>,
    val links: List<AiLink>,
    val dailyLoads: List<DailyLoad> = emptyList(),
    val progress: List<ExerciseProgress> = emptyList(),
    val body: AiBodyDto? = null,
    val cycle: CycleStatus? = null,
    val predictedCycles: List<PredictedCycle> = emptyList(),
)

object AiSnapshotBuilder {

    const val DAYS_BACK: Int = 28
    const val DAYS_AHEAD: Int = 84

    fun build(input: AiSnapshotInput): AiSnapshot {
        val from = input.today - DAYS_BACK
        val to = input.today + DAYS_AHEAD
        val links = input.links.associateBy { it.kind to it.localKey }
        val aiExerciseIds = input.links.filter { it.kind == AiKind.EXERCISE }.mapTo(mutableSetOf()) { it.localKey }
        val groups = input.groups + AiDataGroup.BASICS

        fun inWindow(day: Long) = day in from..to

        return AiSnapshot(
            generatedAtMillis = input.nowMillis,
            today = iso(input.today),
            groups = AiDataGroup.entries.filter { it in groups }.map { it.name },
            window = AiWindow(iso(from), iso(to)),
            sports = input.sports,
            equipment = input.equipment.sortedBy { it.ordinal }.map { it.name },
            exercises = input.exercises.map { e ->
                AiExerciseDto(
                    id = e.id, name = e.name, primary = e.primary.map { it.name }, secondary = e.secondary.map { it.name },
                    equipment = e.equipment.name, pattern = e.pattern.name, timed = e.isTimed, oneSided = e.unilateral,
                    source = when (e.id) {
                        in aiExerciseIds -> "ai"
                        in input.ownExerciseIds -> "mine"
                        else -> "catalog"
                    },
                )
            },
            workouts = input.workouts.map { w ->
                AiWorkoutDto(
                    id = AiIds.workout(w.id), name = w.name, kind = w.kind.name, minutes = w.estimatedMinutes,
                    rows = w.exercises.sortedBy { it.orderIndex }.map { r ->
                        AiWorkoutRow(r.exerciseId, r.sets, r.reps, r.seconds, r.loadKg, r.restSec, r.note)
                    },
                    video = w.videoUrl != null, byAi = (AiKind.WORKOUT to w.id.toString()) in links, notes = w.notes,
                )
            },
            goals = input.goals.filter { it.status == GoalStatus.ACTIVE }.sortedBy { it.priority }.map { g ->
                AiGoalDto(
                    title = g.title, type = g.type.name, day = g.targetDay?.let(::iso),
                    distanceKm = g.targetDistanceMeters?.let { km(it) }, timeSec = g.targetTimeSec, priority = g.priority,
                )
            },
            sessions = input.sessions.filter { inWindow(it.day) }.sortedWith(compareBy({ it.day }, { it.startMinuteOfDay ?: 0 })).map { s ->
                AiSessionDto(
                    id = AiIds.session(s.id), day = iso(s.day), start = s.startMinuteOfDay?.let(::hhmm),
                    sport = s.sportType.name, sessionType = s.sessionType.name, intensity = s.intensity.name,
                    durationMin = s.targetDurationMin, distanceKm = s.targetDistanceMeters?.let { km(it) },
                    pace = s.targetPaceSecPerKm?.let(::pace), loadAu = s.estimatedTrimp?.let(::one),
                    workout = s.workoutId?.let(AiIds::workout), description = s.description, status = s.status.name,
                    locked = s.locked, changeable = isChangeable(s, input.today),
                    byAi = (AiKind.SESSION to s.id.toString()) in links,
                )
            },
            events = input.events.filter { inWindow(it.startDay) }.sortedWith(compareBy({ it.startDay }, { it.startMinuteOfDay ?: 0 })).map { e ->
                AiEventDto(
                    day = iso(e.startDay), start = e.startMinuteOfDay?.let(::hhmm), durationMin = e.durationMin, title = e.title,
                    type = e.type.name, sport = e.sportType?.name, intensity = e.intensity?.name, key = e.isKeyEvent,
                )
            },
            activities = input.activities.filter { it.day in from..input.today }.sortedBy { it.startAtMillis }.map { a ->
                AiActivityDto(
                    day = iso(a.day), sport = a.sportType.name, durationMin = (a.durationSec + 30) / 60,
                    distanceKm = a.distanceMeters?.let { km(it) }, loadAu = input.activityLoad[a.id]?.let(::one), avgHr = a.avgHr,
                )
            },
            links = input.links.associate { l ->
                l.aiId to when (l.kind) {
                    AiKind.EXERCISE -> l.localKey
                    AiKind.WORKOUT -> "w${l.localKey}"
                    AiKind.SESSION -> "s${l.localKey}"
                }
            },
            load = if (AiDataGroup.LOAD in groups) {
                AiLoadDto(
                    days = input.dailyLoads.filter { it.day in from..input.today }.sortedBy { it.day }.map { d ->
                        AiDayLoad(iso(d.day), one(d.trimp), one(d.ctl), one(d.atl), d.acwr?.let(::two), d.recoveryScore)
                    },
                    progress = input.progress.map { p -> AiProgressDto(p.exerciseId, p.loadKg, p.reps, p.seconds, p.lastFeedback?.name) },
                )
            } else {
                null
            },
            body = input.body.takeIf { AiDataGroup.BODY in groups },
            cycle = input.cycle?.takeIf { AiDataGroup.CYCLE in groups }?.let { c ->
                AiCycleDto(
                    phase = c.phase.name, dayOfCycle = c.dayOfCycle, nextPeriodStart = iso(c.nextPeriodStart), predicted = c.isPredicted,
                    periodsAhead = input.predictedCycles.filter { it.periodEnd >= input.today && it.periodStart <= to }
                        .map { AiWindow(iso(it.periodStart), iso(it.periodEnd)) },
                )
            },
        )
    }

    /** The same rule [AiOpPlanner] applies before it changes or deletes a session. */
    fun isChangeable(s: PlannedSession, today: Long): Boolean =
        !s.locked && s.status == PlannedStatus.PLANNED && s.linkedActivityId == null && s.day >= today

    fun iso(day: Long): String = LocalDate.fromEpochDays(day.toInt()).toString()

    fun hhmm(minute: Int): String = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"

    fun pace(sec: Int): String = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

    private fun km(meters: Double): Double = round(meters / 10.0) / 100.0
    private fun one(v: Double): Double = round(v * 10.0) / 10.0
    private fun two(v: Double): Double = round(v * 100.0) / 100.0
}
