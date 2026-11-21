package com.myhealth.data.video

import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.timeZone
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.engine.strength.video.VideoDone
import com.myhealth.domain.engine.strength.video.VideoEffort
import com.myhealth.domain.engine.suggest.SessionCatalog
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.LoadRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.repository.VideoSessions
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.first

/**
 * Done / undone for sessions without a watch recording (P26.5). "Done" on a session that already
 * has a linked recording only completes it; otherwise a `MANUAL` estimate activity
 * ([VideoDone.estimateActivity]) is created, linked and the load recomputed. Reopening, skipping or
 * deleting such a session first removes its estimate ([releaseEstimate]), so nothing double counts.
 */
class VideoSessionService(
    private val planRepo: PlanRepository,
    private val activityRepo: ActivityRepository,
    private val loadRepo: LoadRepository,
    private val strengthRepo: StrengthRepository,
    private val clock: PlatformClock,
) : VideoSessions {

    override suspend fun markDone(sessionId: Long, durationMin: Int, effort: VideoEffort): Outcome<Unit> {
        val session = planRepo.getSession(sessionId) ?: return notFound()
        if (session.linkedActivityId == null) {
            val workout = session.workoutId?.let { strengthRepo.getById(it) }
            val estimate = VideoDone.estimateActivity(
                title = workout?.name.orEmpty(),
                kind = workout?.kind ?: StrengthWorkoutKind.FULL,
                day = session.day,
                durationMin = durationMin,
                effort = effort,
                nowMillis = clock.millis(),
                today = clock.todayEpochDay(),
                zone = clock.timeZone,
            )
            val activityId = when (val r = activityRepo.upsert(estimate)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
            planRepo.linkActivity(sessionId, activityId)
        }
        val result = planRepo.setSessionStatus(sessionId, PlannedStatus.COMPLETED)
        loadRepo.recomputeFrom(session.day)
        return result
    }

    /** "Done today" from the workout screen: a session for today, then [markDone]. */
    override suspend fun doneToday(workoutId: Long, durationMin: Int, effort: VideoEffort): Outcome<Unit> {
        val workout = strengthRepo.getById(workoutId) ?: return notFound()
        val sessionId = when (val r = planRepo.upsertSession(sessionFor(workout, clock.todayEpochDay()))) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
        return markDone(sessionId, durationMin, effort)
    }

    /**
     * "Which workout was this?" on a recording no session claims: a completed session on its day
     * that runs [workoutId] and is linked to it, so muscle load uses that workout's exercises.
     */
    override suspend fun linkWorkout(activityId: Long, workoutId: Long): Outcome<Unit> {
        val activity = activityRepo.getById(activityId) ?: return notFound()
        val workout = strengthRepo.getById(workoutId) ?: return notFound()
        val session = sessionFor(workout, activity.day).copy(
            status = PlannedStatus.COMPLETED,
            linkedActivityId = activityId,
            targetDurationMin = activity.durationSec / 60,
        )
        return when (val r = planRepo.upsertSession(session)) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Err -> r
        }
    }

    /** Removes the estimate a completed session carries (no-op for a real recording). */
    override suspend fun releaseEstimate(sessionId: Long) {
        val session = planRepo.getSession(sessionId) ?: return
        val activityId = session.linkedActivityId ?: return
        val activity = activityRepo.observeRange(session.day, session.day).first().firstOrNull { it.id == activityId } ?: return
        if (!VideoDone.isEstimate(activity)) return
        planRepo.linkActivity(sessionId, null)
        activityRepo.delete(activityId)
        loadRepo.recomputeFrom(session.day)
    }

    private suspend fun sessionFor(workout: StrengthWorkout, day: Long): PlannedSession {
        val now = clock.millis()
        val type = workout.kind.sessionType
        val minutes = workout.estimatedMinutes
        return PlannedSession(
            id = 0L,
            planId = planRepo.observeActivePlan().first()?.id,
            day = day,
            startMinuteOfDay = null,
            sportType = SportType.STRENGTH,
            sessionType = type,
            intensity = Intensity.MODERATE,
            targetDurationMin = minutes,
            targetDistanceMeters = null,
            targetPaceSecPerKm = null,
            estimatedTrimp = SessionCatalog.entryFor(type)?.estTrimpFor(minutes),
            description = null,
            rationale = null,
            status = PlannedStatus.PLANNED,
            locked = false,
            linkedActivityId = null,
            sourceSuggestionId = null,
            createdAtMillis = now,
            updatedAtMillis = now,
            workoutId = workout.id,
        )
    }

    private fun notFound(): Outcome<Unit> = Outcome.Err(AppError.Validation("id", "session, workout or activity not found"))
}
