package com.myhealth.domain.engine.activity

import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySource.CSV_IMPORT
import com.myhealth.domain.model.ActivitySource.FIT_IMPORT
import com.myhealth.domain.model.ActivitySource.GARMIN_API
import com.myhealth.domain.model.ActivitySource.HEALTH_CONNECT
import com.myhealth.domain.model.ActivitySource.MANUAL

/**
 * The field-precedence table of PLAN §2.4. A source missing from a group's list ranks after every
 * listed one (e.g. `MANUAL` never wins a distance).
 */
enum class MergeFieldGroup(val precedence: List<ActivitySource>) {
    /** Streams, laps and the heart-rate summaries derived from them: device files win. */
    STREAMS(listOf(FIT_IMPORT, GARMIN_API, HEALTH_CONNECT)),

    /**
     * Distance, duration, speed, cadence, elevation, **cycling power** and the session's time
     * span. Power joins this group rather than [STREAMS] because the CSV carries session power
     * (avg / max / NP) without any stream at all, and `CSV_IMPORT` is not in [STREAMS]' list.
     */
    MOTION(listOf(FIT_IMPORT, HEALTH_CONNECT, GARMIN_API, CSV_IMPORT)),

    /** Calories: Health Connect carries Garmin's own, device-calibrated kcal. */
    ENERGY(listOf(HEALTH_CONNECT, FIT_IMPORT, GARMIN_API, CSV_IMPORT)),

    /** Title and sport type: Health Connect types are coarse, so it ranks last. */
    IDENTITY(listOf(FIT_IMPORT, CSV_IMPORT, GARMIN_API, HEALTH_CONNECT)),

    /** `rpe` and `note`: a manual entry always wins. */
    MANUAL_FIRST(listOf(MANUAL, FIT_IMPORT, CSV_IMPORT, GARMIN_API, HEALTH_CONNECT)),
    ;

    fun rank(source: ActivitySource): Int =
        precedence.indexOf(source).let { if (it >= 0) it else precedence.size }

    /** Best (lowest) rank among [sources] — how well a merged row can speak for this group. */
    fun rank(sources: List<ActivitySource>): Int =
        sources.minOfOrNull { rank(it) } ?: precedence.size
}

/** `userEditedFieldsCsv` entries — the domain property names a merge must never overwrite. */
object ActivityFields {
    const val START_AT_MILLIS = "startAtMillis"
    const val END_AT_MILLIS = "endAtMillis"
    const val SPORT_TYPE = "sportType"
    const val TITLE = "title"
    const val DURATION_SEC = "durationSec"
    const val ELAPSED_SEC = "elapsedSec"
    const val DISTANCE_METERS = "distanceMeters"
    const val ACTIVE_ENERGY_KCAL = "activeEnergyKcal"
    const val TOTAL_ENERGY_KCAL = "totalEnergyKcal"
    const val AVG_HR = "avgHr"
    const val MAX_HR = "maxHr"
    const val AVG_SPEED_MPS = "avgSpeedMps"
    const val MAX_SPEED_MPS = "maxSpeedMps"
    const val AVG_CADENCE_SPM = "avgCadenceSpm"
    const val ELEVATION_GAIN_M = "elevationGainM"
    const val AVG_POWER_W = "avgPowerW"
    const val MAX_POWER_W = "maxPowerW"
    const val NORMALIZED_POWER_W = "normalizedPowerW"
    const val TRIMP = "trimp"
    const val RPE = "rpe"
    const val NOTE = "note"
}

/**
 * Merges the per-source views of one real-world activity into the canonical row (PLAN §2.4).
 *
 * [candidates] are normalized single-source sessions (one per `activity_source_record` that just
 * arrived); [existing] is the canonical row already in the database, or `null` for a brand-new
 * activity. The result keeps [existing]'s `id`, `createdAtMillis` and `userEditedFields`.
 *
 * Why [existing] takes part as a pseudo-candidate: the canonical row has no per-field provenance
 * column, but its `mergedSources` list says which sources fed it, so for every field group it is
 * ranked by the **best** source it contains. That makes the merge order-independent — ingesting
 * Health Connect then FIT yields exactly the same row as FIT then Health Connect — and stops a
 * re-ingest of one source from pulling back a field another source legitimately won. Ties go to
 * the incoming candidate, so re-ingesting a record whose payload changed does update the row.
 */
object ActivityMerger {

    /** Order used for `primarySource` — the motion precedence, with `MANUAL` last. */
    val PRIMARY_ORDER: List<ActivitySource> = MergeFieldGroup.MOTION.precedence + MANUAL

    fun merge(
        candidates: List<ActivitySession>,
        existing: ActivitySession?,
        nowMillis: Long,
    ): ActivitySession {
        require(candidates.isNotEmpty() || existing != null) { "merge needs at least one input" }
        val entries = buildList {
            candidates.forEach { add(Entry(it, listOf(it.primarySource))) }
            existing?.let { add(Entry(it, it.mergedSources.ifEmpty { listOf(it.primarySource) })) }
        }
        val byGroup = MergeFieldGroup.entries.associateWith { group ->
            entries.sortedBy { group.rank(it.sources) }
        }
        val protected = existing?.userEditedFields.orEmpty().toSet()

        fun <T> pick(field: String, group: MergeFieldGroup, of: (ActivitySession) -> T?): T? =
            if (existing != null && field in protected) {
                of(existing)
            } else {
                byGroup.getValue(group).firstNotNullOfOrNull { of(it.session) }
            }

        val motion = byGroup.getValue(MergeFieldGroup.MOTION).first().session
        val sportType = pick(ActivityFields.SPORT_TYPE, MergeFieldGroup.IDENTITY) { it.sportType }
            ?: motion.sportType
        val start = pick(ActivityFields.START_AT_MILLIS, MergeFieldGroup.MOTION) { it.startAtMillis }
            ?: motion.startAtMillis
        val streams = pick("streams", MergeFieldGroup.STREAMS) { it.streams }
        val sources = mergedSourcesOf(candidates, existing)

        return ActivitySession(
            id = existing?.id ?: 0L,
            startAtMillis = start,
            endAtMillis = pick(ActivityFields.END_AT_MILLIS, MergeFieldGroup.MOTION) { it.endAtMillis }
                ?: motion.endAtMillis,
            day = pick("day", MergeFieldGroup.MOTION) { it.day } ?: motion.day,
            sportType = sportType,
            sportGroup = sportType.group,
            title = pick(ActivityFields.TITLE, MergeFieldGroup.IDENTITY) { it.title },
            durationSec = pick(ActivityFields.DURATION_SEC, MergeFieldGroup.MOTION) { it.durationSec }
                ?: motion.durationSec,
            elapsedSec = pick(ActivityFields.ELAPSED_SEC, MergeFieldGroup.MOTION) { it.elapsedSec }
                ?: motion.elapsedSec,
            distanceMeters = pick(ActivityFields.DISTANCE_METERS, MergeFieldGroup.MOTION) { it.distanceMeters },
            activeEnergyKcal = pick(ActivityFields.ACTIVE_ENERGY_KCAL, MergeFieldGroup.ENERGY) { it.activeEnergyKcal },
            totalEnergyKcal = pick(ActivityFields.TOTAL_ENERGY_KCAL, MergeFieldGroup.ENERGY) { it.totalEnergyKcal },
            avgHr = pick(ActivityFields.AVG_HR, MergeFieldGroup.STREAMS) { it.avgHr },
            maxHr = pick(ActivityFields.MAX_HR, MergeFieldGroup.STREAMS) { it.maxHr },
            avgSpeedMps = pick(ActivityFields.AVG_SPEED_MPS, MergeFieldGroup.MOTION) { it.avgSpeedMps },
            maxSpeedMps = pick(ActivityFields.MAX_SPEED_MPS, MergeFieldGroup.MOTION) { it.maxSpeedMps },
            avgCadenceSpm = pick(ActivityFields.AVG_CADENCE_SPM, MergeFieldGroup.MOTION) { it.avgCadenceSpm },
            elevationGainM = pick(ActivityFields.ELEVATION_GAIN_M, MergeFieldGroup.MOTION) { it.elevationGainM },
            // Each power field is picked independently, so a CSV-only normalized power survives
            // even when a higher-ranked source supplied the average and maximum (P12).
            avgPowerW = pick(ActivityFields.AVG_POWER_W, MergeFieldGroup.MOTION) { it.avgPowerW },
            maxPowerW = pick(ActivityFields.MAX_POWER_W, MergeFieldGroup.MOTION) { it.maxPowerW },
            normalizedPowerW = pick(ActivityFields.NORMALIZED_POWER_W, MergeFieldGroup.MOTION) {
                it.normalizedPowerW
            },
            // TRIMP and its method are computed by the load engine (§3.2), never by a source.
            trimp = pick(ActivityFields.TRIMP, MergeFieldGroup.MOTION) { it.trimp },
            loadMethod = pick("loadMethod", MergeFieldGroup.MOTION) { it.loadMethod },
            rpe = pick(ActivityFields.RPE, MergeFieldGroup.MANUAL_FIRST) { it.rpe },
            note = pick(ActivityFields.NOTE, MergeFieldGroup.MANUAL_FIRST) { it.note },
            primarySource = primarySourceOf(sources),
            mergedSources = sources,
            dedupeBucket = DedupeKey.of(sportType.group, start),
            userEditedFields = existing?.userEditedFields.orEmpty(),
            hasStreams = streams != null,
            streams = streams,
            laps = pick("laps", MergeFieldGroup.STREAMS) { it.laps.ifEmpty { null } }.orEmpty(),
            createdAtMillis = existing?.createdAtMillis
                ?: candidates.minOfOrNull { it.createdAtMillis }
                ?: nowMillis,
            updatedAtMillis = nowMillis,
        )
    }

    /** Every source that has ever fed this activity, in enum order so the CSV is deterministic. */
    fun mergedSourcesOf(
        candidates: List<ActivitySession>,
        existing: ActivitySession?,
    ): List<ActivitySource> =
        (existing?.mergedSources.orEmpty() + candidates.map { it.primarySource })
            .distinct()
            .sortedBy { it.ordinal }

    /** "Source of the winning field set" (§2.2.2): the best-ranked source that fed the row. */
    fun primarySourceOf(sources: List<ActivitySource>): ActivitySource =
        PRIMARY_ORDER.firstOrNull { it in sources } ?: sources.firstOrNull() ?: MANUAL

    private class Entry(val session: ActivitySession, val sources: List<ActivitySource>)
}
