package com.myhealth.domain.model

import com.myhealth.domain.util.EngineWarning

/**
 * Mirrors `daily_load` (PLAN §2.2.6), plus richer detail than the cached row: the engine keeps
 * [flags] as a typed list rather than the entity's `flagsCsv` (§2.3).
 */
data class DailyLoad(
    val day: Long,
    val trimp: Double,
    val sessionCount: Int,
    val atl: Double,
    val ctl: Double,
    val acwr: Double?,
    val tsb: Double,
    val monotony: Double?,
    val strain: Double?,
    val recoveryScore: Int?,
    val recoveryBand: RecoveryBand?,
    val recoveryConfidence: Double,
    val flags: List<String>,
    val computedAtMillis: Long,
)

/** One weighted component of the recovery score (§3.3), e.g. sleep, resting HR, load, HRV. */
data class RecoveryComponent(val name: String, val points: Double, val maxPoints: Double)

/**
 * Output of `RecoveryEngine` (§3.3). [score]/[band] are null when no component could be computed
 * at all; consumers must handle that case.
 */
data class RecoveryState(
    val day: Long,
    val score: Int?,
    val band: RecoveryBand?,
    val confidence: Double,
    val components: List<RecoveryComponent>,
    val flags: List<String>,
    val warnings: List<EngineWarning>,
)

/** Mirrors `running_best` (§2.2.6). PR per distance = `MIN(timeSec)` across all rows. */
data class RunningBest(
    val id: Long,
    val distanceMeters: Double,
    val timeSec: Int,
    val activityId: Long?,
    val day: Long,
    /** `FULL_ACTIVITY` / `BEST_SPLIT` / `MANUAL`. */
    val method: String,
    val isEstimated: Boolean,
    val paceSecPerKm: Int,
    val createdAtMillis: Long,
)

/**
 * Mirrors `ride_best` (§2.2.6, P12). One row per qualifying effort, exactly like `running_best`:
 * the PR of a kind is `MAX(value)` for [RideBestKind.isPower] kinds and `MIN(value)` for the
 * `TIME_*` kinds (see `RideBestDao.observeBestPerKind`).
 *
 * [value] is watts for a `POWER_*` kind and seconds for a `TIME_*` kind. [isEstimated] marks an
 * effort derived from sparse samples or scaled from the whole ride rather than an exact split.
 */
data class RideBest(
    val id: Long,
    val kind: RideBestKind,
    val value: Double,
    val activityId: Long?,
    val day: Long,
    val isEstimated: Boolean,
    val createdAtMillis: Long,
)

/**
 * What undoing one import removed (§2.2.6). [activitiesDeleted] only counts canonical rows whose
 * last source record was this import's; an activity Health Connect also knows is re-merged from
 * the sources that remain and counted in [activitiesKept].
 *
 * Also reused by `ImportRepository.removeOrphanedImportData` (BUG-12b hotfix 1.0.3), which is not
 * tied to a single import: [importId] is `0` there, since the file imports it sweeps up predate
 * DB v4 and their own `import_record` rows may already be gone.
 */
data class ImportUndoSummary(
    val importId: Long,
    val sourceRecordsRemoved: Int,
    val activitiesDeleted: Int,
    val activitiesKept: Int,
)

/** Mirrors `import_record` (§2.2.6). */
data class ImportRecord(
    val id: Long,
    val kind: ImportKind,
    val fileName: String,
    val fileHashSha256: String,
    val importedAtMillis: Long,
    val itemsParsed: Int,
    val itemsInserted: Int,
    val itemsDuplicate: Int,
    val errorsJson: String?,
)
