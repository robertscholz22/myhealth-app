package com.myhealth.domain.engine.calendar

import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.EventOccurrence
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.math.abs
import kotlin.math.floor

/**
 * One candidate link between an expanded event occurrence and an activity (PLAN P3.3).
 * [autoApply] is true only for a confident, unambiguous match — everything else is shown to the
 * user as a suggestion (P3.7).
 */
data class LinkProposal(
    val eventOccurrence: EventOccurrence,
    val activity: ActivitySummary,
    val confidence: Double,
    val autoApply: Boolean,
)

/**
 * Matches event occurrences to recorded activities (PLAN P3.3, normative formula):
 *
 * ```
 * overlapRatio = overlapMinutes / min(eventDuration, activityDuration)
 * sportScore   = 1.0 same SportType · 0.8 same SportGroup · 0.0 otherwise
 * startScore   = 1.0 - clamp(|startDelta| / 90min, 0, 1)
 * confidence   = 0.5*overlapRatio + 0.3*sportScore + 0.2*startScore
 * ```
 *
 * An all-day event (no `startMinuteOfDay`) uses `overlapRatio = 1.0` for any activity on that day
 * and `startScore = 0.5`. Candidates are activities on the occurrence's local day that are not
 * already linked to some event; occurrences that already carry a link are skipped.
 *
 * Confidence is rounded to six decimals before it is compared with the thresholds, so a
 * case that is exactly on a boundary on paper is exactly on the boundary in code too (binary
 * doubles otherwise land a few ulps below, e.g. `0.25 + 0.3 < 0.55`).
 */
object EventActivityLinker {

    /** Show it to the user at or above this confidence. */
    const val PROPOSE_THRESHOLD = 0.55

    /** Link it without asking at or above this confidence — if it is the unique best match. */
    const val AUTO_APPLY_THRESHOLD = 0.80

    /** `startScore` reaches 0 at this start delta. */
    const val START_WINDOW_MIN = 90.0

    /** Duration assumed for a timed event that carries no duration. */
    const val DEFAULT_EVENT_DURATION_MIN = 60.0

    /** `10^6` — confidence is rounded to six decimals before it meets a threshold. */
    private const val PRECISION_SCALE = 1_000_000.0
    private const val MINUTES_PER_DAY = 1440.0

    /**
     * All proposals at or above [PROPOSE_THRESHOLD], ordered by confidence (descending) and then
     * by activity id so the list is deterministic.
     */
    fun propose(
        occurrences: List<EventOccurrence>,
        activities: List<ActivitySummary>,
        zone: TimeZone,
    ): List<LinkProposal> {
        val linkedActivityIds = occurrences.mapNotNull { it.linkedActivityId }.toSet()
        val candidates = activities.filterNot { it.id in linkedActivityIds }
        val perOccurrence = occurrences
            .filter { it.linkedActivityId == null }
            .map { occurrence -> proposalsFor(occurrence, candidates, zone) }

        // An activity may only be auto-linked once, even if two events both match it confidently.
        val autoCounts = perOccurrence
            .mapNotNull { it.firstOrNull { proposal -> proposal.autoApply }?.activity?.id }
            .groupingBy { it }
            .eachCount()

        return perOccurrence
            .flatten()
            .map { if (it.autoApply && (autoCounts[it.activity.id] ?: 0) > 1) it.copy(autoApply = false) else it }
            .sortedWith(compareByDescending<LinkProposal> { it.confidence }.thenBy { it.activity.id })
    }

    /** Proposals for one occurrence, best first; at most one of them can be [LinkProposal.autoApply]. */
    private fun proposalsFor(
        occurrence: EventOccurrence,
        activities: List<ActivitySummary>,
        zone: TimeZone,
    ): List<LinkProposal> {
        val scored = activities
            .filter { it.day == occurrence.occurrenceDay }
            .map { activity -> activity to confidence(occurrence, activity, zone) }
            .filter { (_, confidence) -> confidence >= PROPOSE_THRESHOLD }
            .sortedWith(compareByDescending<Pair<ActivitySummary, Double>> { it.second }.thenBy { it.first.id })

        val bestConfidence = scored.firstOrNull()?.second ?: return emptyList()
        val uniqueBest = bestConfidence >= AUTO_APPLY_THRESHOLD &&
            scored.drop(1).none { it.second >= bestConfidence }

        return scored.mapIndexed { index, (activity, confidence) ->
            LinkProposal(
                eventOccurrence = occurrence,
                activity = activity,
                confidence = confidence,
                autoApply = uniqueBest && index == 0,
            )
        }
    }

    /** The §P3.3 confidence of linking [activity] to [occurrence]; 0 when they do not overlap. */
    fun confidence(occurrence: EventOccurrence, activity: ActivitySummary, zone: TimeZone): Double {
        if (activity.day != occurrence.occurrenceDay) return 0.0
        val sportScore = sportScore(occurrence, activity)
        val startMinute = occurrence.effectiveStartMinuteOfDay
        val (overlapRatio, startScore) = if (startMinute == null) {
            1.0 to 0.5
        } else {
            timedScores(occurrence, startMinute, activity, zone)
        }
        return round(0.5 * overlapRatio + 0.3 * sportScore + 0.2 * startScore)
    }

    private fun sportScore(occurrence: EventOccurrence, activity: ActivitySummary): Double {
        val eventSport = occurrence.sportType ?: return 0.0
        return when {
            eventSport == activity.sportType -> 1.0
            eventSport.group == activity.sportGroup -> 0.8
            else -> 0.0
        }
    }

    /** `overlapRatio to startScore` for a timed event, in minutes from the local epoch. */
    private fun timedScores(
        occurrence: EventOccurrence,
        startMinuteOfDay: Int,
        activity: ActivitySummary,
        zone: TimeZone,
    ): Pair<Double, Double> {
        val eventStart = occurrence.occurrenceDay * MINUTES_PER_DAY + startMinuteOfDay
        val eventDuration = occurrence.effectiveDurationMin?.toDouble()?.takeIf { it > 0.0 }
            ?: DEFAULT_EVENT_DURATION_MIN
        val activityStart = localMinutes(activity.startAtMillis, zone)
        val activityDuration = activity.durationSec / 60.0

        val overlap = minOf(eventStart + eventDuration, activityStart + activityDuration) -
            maxOf(eventStart, activityStart)
        val shorter = minOf(eventDuration, activityDuration)
        val overlapRatio = if (shorter <= 0.0) 0.0 else (overlap / shorter).coerceIn(0.0, 1.0)
        val startScore = 1.0 - (abs(activityStart - eventStart) / START_WINDOW_MIN).coerceIn(0.0, 1.0)
        return overlapRatio to startScore
    }

    /** Minutes since the local epoch — the same scale event days/minutes live on. */
    private fun localMinutes(atMillis: Long, zone: TimeZone): Double {
        val local = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
        return local.date.toEpochDays() * MINUTES_PER_DAY + local.time.toSecondOfDay() / 60.0
    }

    /** Half-up rounding to six decimals (amendment A4 in §0.0). */
    private fun round(value: Double): Double =
        floor(value * PRECISION_SCALE + 0.5) / PRECISION_SCALE
}
