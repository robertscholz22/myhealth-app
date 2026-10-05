package com.myhealth.domain.engine.running

import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.engine.load.HR_ZONE_COUNT
import com.myhealth.domain.engine.load.SessionZoneTargets
import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import kotlin.math.abs

/** How much of a zone's pace band is measured and how much is Daniels' table (PLAN §3.10.2). */
enum class PaceConfidence { HIGH, MEDIUM, LOW, MODELLED, NONE }

/**
 * The pace the athlete actually runs one heart-rate zone at (PLAN §3.10.2).
 *
 * [centreSecPerKm] is the weighted median of the in-zone samples, blended with the Daniels anchor
 * when there is not enough of them; [lowSecPerKm]…[highSecPerKm] is the weighted inter-quartile
 * range, or `centre × [1∓w]` whenever the anchor took part. All three are `null` — and only then —
 * when [confidence] is [PaceConfidence.NONE]: no samples and no VDOT means no band at all, and the
 * screen says "not enough data yet" rather than inventing a number.
 *
 * [inZoneSec] and [activities] are the evidence the confidence was derived from, and are reported
 * even when they were too thin to use (a zone with 40 s of samples is `NONE`/`MODELLED`, not `LOW`).
 */
data class PaceZoneBand(
    val zone: Int,
    val centreSecPerKm: Int?,
    val lowSecPerKm: Int?,
    val highSecPerKm: Int?,
    val confidence: PaceConfidence,
    val inZoneSec: Double,
    val activities: Int,
)

/**
 * Zone ↔ pace correlation (PLAN §3.10.2): what pace does *this* athlete's Z2 actually mean?
 *
 * The last [WINDOW_DAYS] days of runs are walked sample by sample; each speed sample is paired with
 * the heart rate [HR_LAG_SEC] seconds later (heart rate lags effort), the pair is filed under the
 * zone of that heart rate, and the zone's band is the weighted median and inter-quartile range of
 * the paces filed under it. The seven sample rules of §3.10.2 are applied before aggregation:
 *
 * 1. the first [WARMUP_SKIP_SEC] s of every activity are skipped (HR is still rising),
 * 2. HR is taken [HR_LAG_SEC] s after the speed sample, and no HR within
 *    ±[HR_MATCH_TOLERANCE_SEC] s of that instant means the sample is dropped,
 * 3. speeds outside `[MIN_SPEED_MPS, MAX_SPEED_MPS]` are dropped (walking, red lights, GPS spikes),
 * 4. each sample weighs the interval to the next one, capped at [MAX_SAMPLE_INTERVAL_SEC] s,
 * 5. grade is ignored — no altitude correction, by decision, not by omission,
 * 6. treadmill runs are excluded unless `includeTreadmillInPrs` (belt calibration ≠ ground pace),
 * 7. a stream-less run contributes one point `(avgHr, durationSec / distanceKm)` weighted by its
 *    duration, and caps its zone's confidence at [PaceConfidence.MEDIUM].
 *
 * Interpretation choices (§3.10.2 is silent on each):
 * - a zone whose samples do not even reach [LOW_MIN_SEC] is treated as *unmeasured* — it falls back
 *   to the anchor exactly like a zone with no samples at all, rather than inventing a sixth rung
 *   below `LOW`; its [PaceZoneBand.inZoneSec] still reports what was seen,
 * - the modelled/blended band is derived from the **rounded** centre (`255 → 247…263` for `pz10`,
 *   which is what §3.10.2 quotes), so the three numbers a screen shows stay consistent,
 * - `speedMps` is the axis; `distanceMeters` is only used to derive speed when a FIT stream has
 *   distance but no speed channel, so a Health-Connect run (speed only) is fully supported.
 *
 * Pure and deterministic: no clock, no repository, no set iteration order in any arithmetic.
 */
object PaceZoneEngine {

    /** `N = 90` days of history (§3.10.2). */
    const val WINDOW_DAYS: Long = 90L

    /** Sample rule 1 — warm-up minutes never describe a zone's pace. */
    const val WARMUP_SKIP_SEC: Int = 600

    /** Sample rule 2 — heart rate lags effort by this much. */
    const val HR_LAG_SEC: Int = 20

    /** Sample rule 2 — how far from `t + lag` an HR reading may sit and still count. */
    const val HR_MATCH_TOLERANCE_SEC: Int = 5

    /** Sample rule 3 — anything slower is walking, standing or stopped. */
    const val MIN_SPEED_MPS: Double = 1.50

    /** Sample rule 3 — anything faster is a GPS spike. */
    const val MAX_SPEED_MPS: Double = 7.00

    /** Sample rule 4 — the `timeInZones` gap cap, so a paused watch cannot inflate a zone. */
    const val MAX_SAMPLE_INTERVAL_SEC: Int = 60

    const val HIGH_MIN_SEC: Double = 1200.0
    const val HIGH_MIN_ACTIVITIES: Int = 3
    const val MEDIUM_MIN_SEC: Double = 300.0
    const val MEDIUM_MIN_ACTIVITIES: Int = 2
    const val LOW_MIN_SEC: Double = 120.0

    /** How much of the blended centre is measured; the rest is the Daniels anchor. */
    fun measuredWeight(confidence: PaceConfidence): Double = when (confidence) {
        PaceConfidence.HIGH -> 1.00
        PaceConfidence.MEDIUM -> 0.75
        PaceConfidence.LOW -> 0.50
        PaceConfidence.MODELLED, PaceConfidence.NONE -> 0.00
    }

    /** Z1…Z5 anchors: the Daniels pace, its multiplier, and the modelled half-width. */
    private val ANCHORS: List<ZoneAnchor> = listOf(
        ZoneAnchor(DanielsPace.EASY, 1.08, 0.05),
        ZoneAnchor(DanielsPace.EASY, 1.00, 0.05),
        ZoneAnchor(DanielsPace.MARATHON, 1.00, 0.04),
        ZoneAnchor(DanielsPace.THRESHOLD, 1.00, 0.03),
        ZoneAnchor(DanielsPace.INTERVAL, 1.00, 0.03),
    )

    /** The Daniels pace zone [zone] is anchored to, in s/km; `null` without a [vdot]. */
    fun anchorSecPerKm(zone: Int, vdot: Double?): Double? {
        val anchor = ANCHORS.getOrNull(zone - 1) ?: return null
        val base = vdot?.let { DanielsPaces.secPerKmExact(it, anchor.pace) } ?: return null
        return base * anchor.multiplier
    }

    /** One [PaceZoneBand] per zone, Z1…Z5 in order. */
    fun compute(input: PaceZoneInput): List<PaceZoneBand> {
        val samples = input.runs
            .filter { eligible(it, input) }
            .flatMap { samplesOf(it, input.zoneModel) }
        return (1..HR_ZONE_COUNT).map { zone ->
            bandFor(zone, samples.filter { it.zone == zone }, input.vdot)
        }
    }

    /**
     * The pace to print on a planned [sessionType] (§3.10.2): the band of the **first** zone of its
     * target range — the training intent sits at the bottom of the range — except `INTERVAL_RUN`,
     * which is prescribed at Z5, and `TEMPO_RUN`, which is prescribed at Z4.
     *
     * `null` when the session type has no zone target at all (strength, soccer, rest).
     */
    fun recommendedPaceFor(sessionType: SessionType, bands: List<PaceZoneBand>): PaceZoneBand? {
        val target = SessionZoneTargets.targetFor(sessionType) ?: return null
        val zone = when (sessionType) {
            SessionType.INTERVAL_RUN -> target.last
            SessionType.TEMPO_RUN -> target.last
            else -> target.first
        }
        return bands.firstOrNull { it.zone == zone }
    }

    // ---- eligibility ------------------------------------------------------------------------

    private fun eligible(run: PaceRun, input: PaceZoneInput): Boolean {
        if (!run.isRun) return false
        if (run.day < input.today - WINDOW_DAYS) return false
        if (run.sportType == SportType.RUN_TREADMILL && !input.includeTreadmillInPrs) return false
        return true
    }

    // ---- sample extraction ------------------------------------------------------------------

    private fun samplesOf(run: PaceRun, model: HrZoneModel): List<PaceSample> {
        val streamSamples = run.streams?.let { streamSamplesOf(run, it, model) }
        return streamSamples ?: listOfNotNull(summarySampleOf(run, model))
    }

    /**
     * Sample rules 1–5 over one activity's HR + speed channels, or `null` when the activity has no
     * usable speed axis at all — only then does the caller fall back to the summary point. An
     * activity whose samples were all *filtered out* (a 9-minute run, rule 1) stays empty: it said
     * nothing about pace, and its average would say less.
     */
    private fun streamSamplesOf(
        run: PaceRun,
        streams: ActivityStreams,
        model: HrZoneModel,
    ): List<PaceSample>? {
        val offsets = streams.sampleOffsetsSec
        val speeds = speedsOf(streams) ?: return null
        val count = minOf(offsets.size, speeds.size)
        if (count < 2) return null

        val out = ArrayList<PaceSample>(count)
        for (i in 0 until count - 1) {
            val t = offsets[i]
            if (t < WARMUP_SKIP_SEC) continue
            val weight = (offsets[i + 1] - t).coerceIn(0, MAX_SAMPLE_INTERVAL_SEC)
            if (weight == 0) continue
            val speed = speeds[i]
            if (!speed.isFinite() || speed < MIN_SPEED_MPS || speed > MAX_SPEED_MPS) continue
            val hr = hrNear(offsets, streams.hr, t + HR_LAG_SEC) ?: continue
            out += PaceSample(
                zone = model.zoneOf(hr),
                paceSecPerKm = METERS_PER_KM / speed,
                weightSec = weight.toDouble(),
                activityId = run.activityId,
                fromSummary = false,
            )
        }
        return out
    }

    /** Sample rule 7 — the single point a stream-less activity is worth. */
    private fun summarySampleOf(run: PaceRun, model: HrZoneModel): PaceSample? {
        val hr = run.avgHr ?: return null
        val distance = run.distanceMeters ?: return null
        if (distance <= 0.0 || run.durationSec <= 0) return null
        val speed = distance / run.durationSec
        if (speed < MIN_SPEED_MPS || speed > MAX_SPEED_MPS) return null
        return PaceSample(
            zone = model.zoneOf(hr),
            paceSecPerKm = run.durationSec / (distance / METERS_PER_KM),
            weightSec = run.durationSec.toDouble(),
            activityId = run.activityId,
            fromSummary = true,
        )
    }

    /** `speedMps` if the source has it, else derived from a FIT distance channel. */
    private fun speedsOf(streams: ActivityStreams): DoubleArray? {
        streams.speedMps?.let { return it }
        val distance = streams.distanceMeters ?: return null
        val offsets = streams.sampleOffsetsSec
        val count = minOf(offsets.size, distance.size)
        if (count < 2) return null
        val speeds = DoubleArray(count)
        for (i in 0 until count - 1) {
            val dt = offsets[i + 1] - offsets[i]
            speeds[i] = if (dt > 0) (distance[i + 1] - distance[i]) / dt else 0.0
        }
        speeds[count - 1] = speeds[count - 2]
        return speeds
    }

    /** First index in `offsets[0 until count]` (ascending) whose value is ≥ [key]. */
    private fun lowerBound(offsets: IntArray, count: Int, key: Int): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The non-null HR reading closest to [targetSec], or `null` beyond ±[HR_MATCH_TOLERANCE_SEC]. */
    private fun hrNear(offsets: IntArray, hr: List<Int?>, targetSec: Int): Int? {
        val count = minOf(offsets.size, hr.size)
        if (count == 0) return null
        var i = lowerBound(offsets, count, targetSec - HR_MATCH_TOLERANCE_SEC)
        var best: Int? = null
        var bestDelta = Int.MAX_VALUE
        while (i < count && offsets[i] <= targetSec + HR_MATCH_TOLERANCE_SEC) {
            val reading = hr[i]
            val delta = abs(offsets[i] - targetSec)
            if (reading != null && delta < bestDelta) {
                best = reading
                bestDelta = delta
            }
            i++
        }
        return best
    }

    // ---- aggregation ------------------------------------------------------------------------

    private fun bandFor(zone: Int, samples: List<PaceSample>, vdot: Double?): PaceZoneBand {
        val totalSec = samples.sumOf { it.weightSec }
        val activities = samples.mapTo(LinkedHashSet()) { it.activityId }.size
        val anchor = anchorSecPerKm(zone, vdot)
        val halfWidth = ANCHORS[zone - 1].halfWidth
        val confidence = ladder(totalSec, activities, samples.any { it.fromSummary })
            ?: return modelled(zone, anchor, halfWidth, totalSec, activities)

        val sorted = samples.sortedWith(compareBy({ it.paceSecPerKm }, { it.activityId }))
        val measured = weightedQuantile(sorted, totalSec, 0.50)
        val weight = measuredWeight(confidence)
        if (anchor == null || weight >= 1.0) {
            return PaceZoneBand(
                zone = zone,
                centreSecPerKm = TrimpDefaults.roundHalfUp(measured),
                lowSecPerKm = TrimpDefaults.roundHalfUp(weightedQuantile(sorted, totalSec, 0.25)),
                highSecPerKm = TrimpDefaults.roundHalfUp(weightedQuantile(sorted, totalSec, 0.75)),
                confidence = confidence,
                inZoneSec = totalSec,
                activities = activities,
            )
        }
        val centre = TrimpDefaults.roundHalfUp(weight * measured + (1.0 - weight) * anchor)
        return spread(zone, centre, halfWidth, confidence, totalSec, activities)
    }

    /** No usable measurement: the anchor alone, or nothing at all. */
    private fun modelled(
        zone: Int,
        anchor: Double?,
        halfWidth: Double,
        totalSec: Double,
        activities: Int,
    ): PaceZoneBand {
        if (anchor == null) {
            return PaceZoneBand(zone, null, null, null, PaceConfidence.NONE, totalSec, activities)
        }
        val centre = TrimpDefaults.roundHalfUp(anchor)
        return spread(zone, centre, halfWidth, PaceConfidence.MODELLED, totalSec, activities)
    }

    /** `centre × [1∓w]`, the band shape used whenever the anchor took part. */
    private fun spread(
        zone: Int,
        centre: Int,
        halfWidth: Double,
        confidence: PaceConfidence,
        totalSec: Double,
        activities: Int,
    ): PaceZoneBand = PaceZoneBand(
        zone = zone,
        centreSecPerKm = centre,
        lowSecPerKm = TrimpDefaults.roundHalfUp(centre * (1.0 - halfWidth)),
        highSecPerKm = TrimpDefaults.roundHalfUp(centre * (1.0 + halfWidth)),
        confidence = confidence,
        inZoneSec = totalSec,
        activities = activities,
    )

    /** The confidence ladder of §3.10.2; `null` when the evidence is too thin to use at all. */
    private fun ladder(totalSec: Double, activities: Int, hasSummaryPoint: Boolean): PaceConfidence? =
        when {
            totalSec >= HIGH_MIN_SEC && activities >= HIGH_MIN_ACTIVITIES ->
                if (hasSummaryPoint) PaceConfidence.MEDIUM else PaceConfidence.HIGH
            totalSec >= MEDIUM_MIN_SEC && activities >= MEDIUM_MIN_ACTIVITIES -> PaceConfidence.MEDIUM
            totalSec >= LOW_MIN_SEC -> PaceConfidence.LOW
            else -> null
        }

    /**
     * The [q]-quantile of [sorted] by accumulated weight: the first pace at which the cumulative
     * weight reaches `q × total`. With five equal weights this is the plain 25th/50th/75th order
     * statistic (`pz05`: 300/310/320/330/340 → 310 · 320 · 330).
     */
    private fun weightedQuantile(sorted: List<PaceSample>, totalSec: Double, q: Double): Double {
        val target = q * totalSec
        var cumulative = 0.0
        for (sample in sorted) {
            cumulative += sample.weightSec
            if (cumulative >= target) return sample.paceSecPerKm
        }
        return sorted.last().paceSecPerKm
    }

    private const val METERS_PER_KM: Double = 1000.0

    private data class ZoneAnchor(
        val pace: DanielsPace,
        val multiplier: Double,
        val halfWidth: Double,
    )

    private data class PaceSample(
        val zone: Int,
        val paceSecPerKm: Double,
        val weightSec: Double,
        val activityId: Long,
        val fromSummary: Boolean,
    )
}
