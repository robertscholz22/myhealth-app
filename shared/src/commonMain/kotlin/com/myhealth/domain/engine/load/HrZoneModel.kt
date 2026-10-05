package com.myhealth.domain.engine.load

import com.myhealth.domain.engine.load.TrimpDefaults as D
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.HrZoneScheme
import com.myhealth.domain.model.Profile
import com.myhealth.domain.util.EngineWarning

/**
 * One named heart-rate zone (PLAN §3.9). [lowBpm] is **inclusive**, [highBpm] is inclusive too and
 * `null` for Z5, which has no ceiling. Z1 has no floor in the sense that anything below Z2 is Z1 —
 * a warm-up minute is Z1, not "below Z1" — so `zones[0].lowBpm` is only a display hint.
 */
data class HrZone(val index: Int, val lowBpm: Int, val highBpm: Int?, val nameKey: String)

/**
 * The five-zone model every prescription in P14 is written in (PLAN §3.9).
 *
 * The boundaries are the lower bounds of Z2…Z5, resolved once per athlete, first match wins:
 *
 * | scheme | condition | boundaries |
 * |---|---|---|
 * | [HrZoneScheme.MANUAL] | `profile.hrZoneBoundsJson` holds four strictly ascending bpm in `(hrRest, hrMax]` | as given |
 * | [HrZoneScheme.LTHR_FRIEL] | `profile.lactateThresholdHrManual != null` | `roundHalfUp(LTHR × [0.81, 0.90, 0.94, 1.00])` |
 * | [HrZoneScheme.HRR_KARVONEN] | always (the default) | `hrRest + roundHalfUp(reserve × [0.60, 0.70, 0.80, 0.90])` |
 *
 * The Karvonen boundaries are the bands `timeInZones` has used since P2.9 (`<60 / 60-70 / 70-80 /
 * 80-90 / >=90 %` of the reserve), which is why the default model reproduces every stored
 * zone-time figure — see `hz06`. The one theoretical difference: a boundary whose fractional part
 * is below `0.5` rounds *down*, so exactly one bpm value (e.g. reserve 142 → `0.6 × 142 = 85.2`)
 * is Z2 for the model and Z1 for the raw band. `timeInZones` itself is untouched, so nothing
 * already computed can move; the model-driven overload is the one that follows the named bands.
 */
data class HrZoneModel(
    val scheme: HrZoneScheme,
    val zones: List<HrZone>,
    val bounds: HrBounds,
    val warnings: List<EngineWarning> = emptyList(),
) {

    /** The zone `hr` falls in, `1..5`; the lower bound belongs to the upper zone. */
    fun zoneOf(hr: Int): Int = zones.last { hr >= it.lowBpm || it.index == 1 }.index

    /** The bpm range of zone [index] (`1..5`); Z5 ends at [HrBounds.hrMax]. */
    fun rangeOf(index: Int): IntRange {
        val zone = zones.firstOrNull { it.index == index } ?: zones.first()
        return zone.lowBpm..(zone.highBpm ?: bounds.hrMax)
    }

    /**
     * Minutes per zone for an activity's HR stream, in Z1…Z5 order — the model-driven counterpart
     * of [timeInZones], with the same 60 s gap cap and the same "a `null` reading skips its whole
     * interval" rule.
     */
    fun minutesPerZone(streams: ActivityStreams): List<Double> =
        timeInZones(streams.sampleOffsetsSec, streams.hr, this)

    companion object {

        /** The lower bounds of Z2…Z5 as fractions of the heart-rate reserve (Karvonen). */
        val KARVONEN_FRACTIONS: List<Double> = listOf(0.60, 0.70, 0.80, 0.90)

        /** The lower bounds of Z2…Z5 as fractions of the lactate-threshold HR (Friel). */
        val FRIEL_FRACTIONS: List<Double> = listOf(0.81, 0.90, 0.94, 1.00)

        /** `hr_zone_1_name`…`hr_zone_5_name` — Recovery, Endurance, Tempo, Threshold, VO2max. */
        val NAME_KEYS: List<String> = (1..HR_ZONE_COUNT).map { "hr_zone_${it}_name" }

        /**
         * Resolves the model for [profile] against the [bounds] the load engine already computed.
         *
         * A manual override that is not four strictly ascending bpm inside `(hrRest, hrMax]` is
         * ignored — with an [EngineWarningCode.IMPLAUSIBLE_VALUE] warning — rather than rejected:
         * the athlete still gets zones, they are just the derived ones (`hz08`).
         */
        fun resolve(profile: Profile, bounds: HrBounds): HrZoneModel {
            val warnings = mutableListOf<EngineWarning>()

            val manual = parseBounds(profile.hrZoneBoundsJson)
            if (profile.hrZoneBoundsJson != null && manual == null) {
                warnings += EngineWarning(
                    EngineWarningCode.IMPLAUSIBLE_VALUE,
                    "Manual heart-rate zone bounds '${profile.hrZoneBoundsJson}' are not four " +
                        "ascending bpm; using the derived zones instead.",
                )
            }
            if (manual != null && !isPlausible(manual, bounds)) {
                warnings += EngineWarning(
                    EngineWarningCode.IMPLAUSIBLE_VALUE,
                    "Manual heart-rate zone bounds $manual are not strictly ascending inside " +
                        "(${bounds.hrRest}, ${bounds.hrMax}]; using the derived zones instead.",
                )
            }

            val lthr = profile.lactateThresholdHrManual
            val (scheme, boundaries) = when {
                manual != null && isPlausible(manual, bounds) -> HrZoneScheme.MANUAL to manual
                lthr != null -> HrZoneScheme.LTHR_FRIEL to
                    FRIEL_FRACTIONS.map { D.roundHalfUp(lthr * it) }
                else -> HrZoneScheme.HRR_KARVONEN to
                    KARVONEN_FRACTIONS.map { bounds.hrRest + D.roundHalfUp(bounds.reserve * it) }
            }
            return HrZoneModel(scheme, zonesOf(boundaries, bounds), bounds, warnings)
        }

        /** Z1…Z5 from the four lower bounds of Z2…Z5. */
        fun zonesOf(boundaries: List<Int>, bounds: HrBounds): List<HrZone> {
            val lows = listOf(bounds.hrRest) + boundaries
            return lows.mapIndexed { i, low ->
                HrZone(
                    index = i + 1,
                    lowBpm = low,
                    highBpm = if (i == lows.lastIndex) null else lows[i + 1] - 1,
                    nameKey = NAME_KEYS[i],
                )
            }
        }

        /** `[z2Start,z3Start,z4Start,z5Start]` from the profile's JSON blob, or `null`. */
        private fun parseBounds(json: String?): List<Int>? {
            val raw = json?.trim()?.removeSurrounding("[", "]") ?: return null
            val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size != HR_ZONE_COUNT - 1) return null
            val values = parts.map { it.toIntOrNull() ?: return null }
            return values
        }

        private fun isPlausible(boundaries: List<Int>, bounds: HrBounds): Boolean =
            boundaries.zipWithNext().all { (a, b) -> a < b } &&
                boundaries.first() > bounds.hrRest &&
                boundaries.last() <= bounds.hrMax
    }
}

/**
 * The 28-day polarisation split of §3.9: how much of the recorded zone time was truly easy and how
 * much was truly hard. Both shares are `0.0` when nothing was recorded, so a fresh install shows
 * "no data" rather than a division by zero.
 */
data class PolarisationSplit(val easyShare: Double, val hardShare: Double, val totalMinutes: Double) {

    companion object {

        /** [minutesPerZone] is a Z1…Z5 list, e.g. the sum of [HrZoneModel.minutesPerZone]. */
        fun of(minutesPerZone: List<Double>): PolarisationSplit {
            val minutes = List(HR_ZONE_COUNT) { minutesPerZone.getOrElse(it) { 0.0 } }
            val total = minutes.sum()
            if (total <= 0.0) return PolarisationSplit(0.0, 0.0, 0.0)
            return PolarisationSplit(
                easyShare = (minutes[0] + minutes[1]) / total,
                hardShare = (minutes[3] + minutes[4]) / total,
                totalMinutes = total,
            )
        }
    }
}
