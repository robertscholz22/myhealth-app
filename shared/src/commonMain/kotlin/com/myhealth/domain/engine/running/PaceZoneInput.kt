package com.myhealth.domain.engine.running

import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType

/**
 * One run offered to [PaceZoneEngine] (PLAN §3.10.2).
 *
 * Either it carries [streams] — the HR and speed channels the pace bands are actually measured
 * from — or it carries nothing but its summary, in which case the single point
 * `(avgHr, durationSec / distanceKm)` stands in for the whole activity (sample rule 7).
 *
 * Health Connect supplies **speed**, not cumulative distance, so [ActivityStreams.speedMps] is the
 * axis; [ActivityStreams.distanceMeters] is a FIT-only bonus the engine falls back on when a FIT
 * import happens to carry distance but no speed. Nothing here requires it.
 */
data class PaceRun(
    val activityId: Long,
    /** Epoch day of the activity — the 90-day window is counted against it. */
    val day: Long,
    val sportType: SportType,
    val durationSec: Int,
    val distanceMeters: Double? = null,
    val avgHr: Int? = null,
    val streams: ActivityStreams? = null,
) {
    val isRun: Boolean get() = sportType.group == SportGroup.RUN

    companion object {

        /** A loaded activity, streams and all. */
        fun of(session: ActivitySession): PaceRun = PaceRun(
            activityId = session.id,
            day = session.day,
            sportType = session.sportType,
            durationSec = session.durationSec,
            distanceMeters = session.distanceMeters,
            avgHr = session.avgHr,
            streams = session.streams,
        )

        /** A list row plus its streams, if the caller loaded them separately. */
        fun of(summary: ActivitySummary, streams: ActivityStreams? = null): PaceRun = PaceRun(
            activityId = summary.id,
            day = summary.day,
            sportType = summary.sportType,
            durationSec = summary.durationSec,
            distanceMeters = summary.distanceMeters,
            avgHr = summary.avgHr,
            streams = streams,
        )
    }
}

/**
 * Everything [PaceZoneEngine.compute] needs (PLAN §3.10.2) — and nothing else: the engine reads no
 * repository and no settings store, so the same input always produces the same bands.
 *
 * [today] is an epoch day; runs older than [PaceZoneEngine.WINDOW_DAYS] are dropped.
 * [includeTreadmillInPrs] is `settings.includeTreadmillInPrs`, passed in rather than looked up
 * (P14.2: "the engine stays pure"). [vdot] is the Daniels anchor; without it a zone with no
 * samples has no band at all.
 */
data class PaceZoneInput(
    val today: Long,
    val runs: List<PaceRun>,
    val zoneModel: HrZoneModel,
    val vdot: Double? = null,
    val includeTreadmillInPrs: Boolean = false,
)
