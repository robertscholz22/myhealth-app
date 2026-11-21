package com.myhealth.domain.repository

import com.myhealth.domain.engine.strength.video.VideoEffort
import com.myhealth.domain.util.Outcome

/** Done / undone for sessions without a watch recording (P26.5); see `VideoSessionService`. */
interface VideoSessions {
    /** Completes [sessionId]; without a linked recording it gets an estimate from [durationMin] × [effort]. */
    suspend fun markDone(sessionId: Long, durationMin: Int, effort: VideoEffort): Outcome<Unit>

    /** A session for today running [workoutId], then [markDone]. */
    suspend fun doneToday(workoutId: Long, durationMin: Int, effort: VideoEffort): Outcome<Unit>

    /** "Which workout was this?": a completed session on the recording's day, linked to it. */
    suspend fun linkWorkout(activityId: Long, workoutId: Long): Outcome<Unit>

    /** Removes the estimate a session carries before it is reopened, skipped or deleted. */
    suspend fun releaseEstimate(sessionId: Long)
}
