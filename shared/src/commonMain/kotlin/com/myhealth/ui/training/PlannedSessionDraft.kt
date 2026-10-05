package com.myhealth.ui.training

import com.myhealth.domain.engine.suggest.SessionCatalog
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/** Field identity for [validatePlannedSession] errors (mirrors `GoalDraft`/`EventDraft`, P3.4). */
enum class PlannedSessionField { DURATION, DISTANCE, PACE, DAY }

/**
 * The planned-session editor's form state (PLAN §4.2 "Planned session edit", P6.8).
 *
 * Distance and pace are held in the units the owner types — kilometres and `min:sec` per kilometre
 * — and converted at the domain boundary ([toPlannedSession]); everything else is stored as it is
 * entered. [estimatedTrimp] is recomputed from the catalog rather than kept, so an edited duration
 * always carries a consistent load estimate into the weekly bar.
 */
data class PlannedSessionDraft(
    val id: Long = 0L,
    val planId: Long? = null,
    val day: LocalDate = LocalDate.fromEpochDays(0),
    val startMinuteOfDay: Int? = null,
    val sportType: SportType = SportType.RUN_OUTDOOR,
    val sessionType: SessionType = SessionType.EASY_RUN,
    val intensity: Intensity = Intensity.LOW,
    val durationMin: Int? = null,
    val distanceKm: Double? = null,
    val paceMinutes: Int? = null,
    val paceSeconds: Int? = null,
    val description: String = "",
    val locked: Boolean = false,
    val status: PlannedStatus = PlannedStatus.PLANNED,
    val linkedActivityId: Long? = null,
    val sourceSuggestionId: Long? = null,
    val createdAtMillis: Long = 0L,
    /** The strength workout this session runs (P14.7, §4.2 "Planned session edit") — offered only
     * for a `STRENGTH_*` session type. */
    val workoutId: Long? = null,
) {
    /** The two pace fields as seconds per kilometre; `null` when neither was entered. */
    val paceSecPerKm: Int?
        get() = if (paceMinutes == null && paceSeconds == null) {
            null
        } else {
            (paceMinutes ?: 0) * SECONDS_PER_MINUTE + (paceSeconds ?: 0)
        }

    /** §3.5.4's `0.30 * rpe * minutes` for the chosen session type, at the entered duration. */
    val estimatedTrimp: Double?
        get() {
            val minutes = durationMin ?: return null
            val entry = SessionCatalog.entryFor(sessionType) ?: return null
            return entry.estTrimpFor(minutes)
        }

    companion object {
        const val SECONDS_PER_MINUTE: Int = 60

        /** A jogged 15:00/km is the slowest thing worth calling a pace; 2:00/km is world-record fast. */
        const val MIN_PACE_SEC_PER_KM: Int = 120
        const val MAX_PACE_SEC_PER_KM: Int = 900

        const val MAX_DURATION_MIN: Int = 600
        const val MAX_DISTANCE_KM: Double = 300.0
    }
}

/** Blocking errors only; an empty map means the draft can be saved. */
fun validatePlannedSession(draft: PlannedSessionDraft): Map<PlannedSessionField, String> {
    val errors = mutableMapOf<PlannedSessionField, String>()
    draft.durationMin?.let { minutes ->
        if (minutes <= 0) {
            errors[PlannedSessionField.DURATION] = "Duration must be more than 0 minutes."
        } else if (minutes > PlannedSessionDraft.MAX_DURATION_MIN) {
            errors[PlannedSessionField.DURATION] = "That is longer than 10 hours."
        }
    }
    draft.distanceKm?.let { km ->
        if (km <= 0.0) {
            errors[PlannedSessionField.DISTANCE] = "Distance must be more than 0 km."
        } else if (km > PlannedSessionDraft.MAX_DISTANCE_KM) {
            errors[PlannedSessionField.DISTANCE] = "That is further than 300 km."
        }
    }
    if ((draft.paceSeconds ?: 0) !in 0..59) {
        errors[PlannedSessionField.PACE] = "Seconds must be 0–59."
    } else {
        draft.paceSecPerKm?.let { pace ->
            if (pace < PlannedSessionDraft.MIN_PACE_SEC_PER_KM ||
                pace > PlannedSessionDraft.MAX_PACE_SEC_PER_KM
            ) {
                errors[PlannedSessionField.PACE] = "Pace must be between 2:00 and 15:00 /km."
            }
        }
    }
    return errors
}

/**
 * The session types that make sense for [sportType] — the editor filters the dropdown by sport.
 *
 * P12.3: the four cycling rows are part of [SessionCatalog.ALL], so `CYCLING` and `CYCLING_INDOOR`
 * both offer `ENDURANCE_RIDE`, `BIKE_INTERVALS`, `TRAINER_SESSION` and `RECOVERY_SPIN` alongside
 * `CROSS_TRAINING` without this function knowing anything about bikes. [sportTypeFor] keeps the
 * chosen sport when it is already in the right group, so an indoor day stays indoor.
 */
fun sessionTypesFor(sportType: SportType): List<SessionType> {
    val catalog = SessionCatalog.ALL
        .filter { it.sportType.group == sportType.group }
        .map { it.sessionType }
    return catalog.ifEmpty { listOf(SessionType.CROSS_TRAINING) }
}

/** The sport a session type implies — picking "Long run" on a bike day is a typo, not a plan. */
fun sportTypeFor(sessionType: SessionType, current: SportType): SportType {
    val entry = SessionCatalog.entryFor(sessionType) ?: return current
    return if (entry.sportType.group == current.group) current else entry.sportType
}

/** The intensity the catalog gives a session type, used as the default when the type changes. */
fun intensityFor(sessionType: SessionType, current: Intensity): Intensity =
    SessionCatalog.entryFor(sessionType)?.intensity ?: current

/** The sports the editor offers, one per [SportGroup] the catalog can plan for. */
val PLANNABLE_SPORT_TYPES: List<SportType> = listOf(
    SportType.RUN_OUTDOOR,
    SportType.RUN_TREADMILL,
    SportType.RUN_TRAIL,
    SportType.STRENGTH,
    SportType.CYCLING,
    SportType.CYCLING_INDOOR,
    SportType.SOCCER_TRAINING,
    SportType.SOCCER_MATCH,
    SportType.MOBILITY,
    SportType.SWIM,
    SportType.WALK,
    SportType.OTHER,
)

/** The domain session the editor saves. */
fun PlannedSessionDraft.toPlannedSession(clock: PlatformClock): PlannedSession {
    val now = clock.millis()
    return PlannedSession(
        id = id,
        planId = planId,
        day = day.toEpochDays(),
        startMinuteOfDay = startMinuteOfDay,
        sportType = sportType,
        sessionType = sessionType,
        intensity = intensity,
        targetDurationMin = durationMin,
        targetDistanceMeters = distanceKm?.let { it * METERS_PER_KM },
        targetPaceSecPerKm = paceSecPerKm,
        estimatedTrimp = estimatedTrimp,
        description = description.trim().takeIf { it.isNotEmpty() },
        rationale = null,
        status = status,
        locked = locked,
        linkedActivityId = linkedActivityId,
        sourceSuggestionId = sourceSuggestionId,
        createdAtMillis = if (id == 0L) now else createdAtMillis,
        updatedAtMillis = now,
        workoutId = workoutId,
    )
}

/** Whether [SessionType] is one of the three `STRENGTH_*` rows (§2.1) — the editor only offers the
 * workout picker, and the card only shows the workout name, for these. */
fun SessionType.isStrength(): Boolean = name.startsWith("STRENGTH_")

/** Whether [SessionType] is `MOBILITY` (§2.1, P17.2) — the editor offers the workout picker for
 * these too, filtered to the three `MOBILITY_*` [com.myhealth.domain.model.StrengthWorkoutKind]s. */
fun SessionType.isMobility(): Boolean = this == SessionType.MOBILITY

/** Loads an existing session into a draft. */
fun plannedSessionDraftOf(session: PlannedSession): PlannedSessionDraft = PlannedSessionDraft(
    id = session.id,
    planId = session.planId,
    day = LocalDate.fromEpochDays(session.day),
    startMinuteOfDay = session.startMinuteOfDay,
    sportType = session.sportType,
    sessionType = session.sessionType,
    intensity = session.intensity,
    durationMin = session.targetDurationMin,
    distanceKm = session.targetDistanceMeters?.let { it / METERS_PER_KM },
    paceMinutes = session.targetPaceSecPerKm?.let { it / PlannedSessionDraft.SECONDS_PER_MINUTE },
    paceSeconds = session.targetPaceSecPerKm?.let { it % PlannedSessionDraft.SECONDS_PER_MINUTE },
    description = session.description.orEmpty(),
    locked = session.locked,
    status = session.status,
    linkedActivityId = session.linkedActivityId,
    sourceSuggestionId = session.sourceSuggestionId,
    createdAtMillis = session.createdAtMillis,
    workoutId = session.workoutId,
)

private const val METERS_PER_KM = 1000.0

fun SessionType.label(): String = trainingLabelOf(name)

fun SportType.planLabel(): String = trainingLabelOf(name)

fun PlannedStatus.label(): String = trainingLabelOf(name)
