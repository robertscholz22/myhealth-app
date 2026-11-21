package com.myhealth.domain.engine.strength.video

import com.myhealth.domain.engine.activity.DedupeKey
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.util.epochDayDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * How hard a video workout done without the watch felt (P26.5). The number is a session RPE: the
 * load engine's existing `RPE_ESTIMATE` rung turns `rpe × minutes` into training load, the same
 * scale the planned-session estimates use.
 */
enum class VideoEffort(val rpe: Int) { EASY(3), MODERATE(5), HARD(7), VERY_HARD(9) }

/** A completed session whose estimate is replaced by the watch recording [syncedActivityId]. */
data class EstimateReplacement(val sessionId: Long, val estimateActivityId: Long, val syncedActivityId: Long)

/**
 * "Done" on a video workout without a watch recording (P26.5): a `MANUAL` activity carrying the
 * duration and effort, linked to the session so training load and muscle load count it. When a
 * synced recording of the same day arrives later, [replacements] swaps it in.
 */
object VideoDone {

    /** Start time of an estimate on a day other than today: noon local. */
    private const val PAST_DAY_START_HOUR = 12
    private const val MILLIS_PER_MINUTE = 60_000L

    fun sportTypeFor(kind: StrengthWorkoutKind): SportType =
        if (kind.isMobility) SportType.MOBILITY else SportType.STRENGTH

    /**
     * The estimate activity for a session on [day]. Today it ends now; on an earlier day it
     * starts at noon. Nothing but duration, effort and title — the recompute fills in the load.
     */
    fun estimateActivity(
        title: String,
        kind: StrengthWorkoutKind,
        day: Long,
        durationMin: Int,
        effort: VideoEffort,
        nowMillis: Long,
        today: Long,
        zone: TimeZone,
    ): ActivitySession {
        val minutes = durationMin.coerceIn(1, 600)
        val durationMillis = minutes * MILLIS_PER_MINUTE
        val start = if (day >= today) {
            nowMillis - durationMillis
        } else {
            LocalDateTime(day.epochDayDate(), kotlinx.datetime.LocalTime(PAST_DAY_START_HOUR, 0))
                .toInstant(zone).toEpochMilliseconds()
        }
        val sportType = sportTypeFor(kind)
        return ActivitySession(
            id = 0L,
            startAtMillis = start,
            endAtMillis = start + durationMillis,
            day = day,
            sportType = sportType,
            sportGroup = sportType.group,
            title = title.trim().ifEmpty { null },
            durationSec = minutes * 60,
            elapsedSec = minutes * 60,
            distanceMeters = null,
            activeEnergyKcal = null,
            totalEnergyKcal = null,
            avgHr = null,
            maxHr = null,
            avgSpeedMps = null,
            maxSpeedMps = null,
            avgCadenceSpm = null,
            elevationGainM = null,
            trimp = null,
            loadMethod = null,
            rpe = effort.rpe,
            note = null,
            primarySource = ActivitySource.MANUAL,
            mergedSources = listOf(ActivitySource.MANUAL),
            dedupeBucket = DedupeKey.of(sportType.group, start),
            userEditedFields = emptyList(),
            hasStreams = false,
            streams = null,
            laps = emptyList(),
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )
    }

    /** True for an activity nothing but a manual entry contributed to — what "Done" creates. */
    fun isEstimate(activity: ActivitySummary): Boolean =
        activity.primarySource == ActivitySource.MANUAL &&
            activity.mergedSources.all { it == ActivitySource.MANUAL }

    /**
     * Completed sessions linked to an estimate whose day also has a recorded activity of the same
     * kind (strength group, or mobility) that no session claims yet. Each recording replaces at
     * most one estimate; the longest recording goes first, ties on the lower id.
     */
    fun replacements(sessions: List<PlannedSession>, activities: List<ActivitySummary>): List<EstimateReplacement> {
        val byId = activities.associateBy { it.id }
        val taken = sessions.mapNotNull { it.linkedActivityId }.toMutableSet()
        val result = mutableListOf<EstimateReplacement>()
        sessions
            .filter { it.status == PlannedStatus.COMPLETED && it.linkedActivityId != null }
            .sortedWith(compareBy({ it.day }, { it.id }))
            .forEach { session ->
                val estimate = byId[session.linkedActivityId] ?: return@forEach
                if (!isEstimate(estimate)) return@forEach
                val recording = activities
                    .filter { it.day == estimate.day && it.id !in taken && !isEstimate(it) && sameKind(it.sportType, estimate.sportType) }
                    .sortedWith(compareByDescending<ActivitySummary> { it.durationSec }.thenBy { it.id })
                    .firstOrNull() ?: return@forEach
                taken += recording.id
                result += EstimateReplacement(session.id, estimate.id, recording.id)
            }
        return result
    }

    private fun sameKind(recorded: SportType, estimate: SportType): Boolean =
        if (estimate == SportType.MOBILITY) recorded == SportType.MOBILITY else recorded.group == estimate.group
}
