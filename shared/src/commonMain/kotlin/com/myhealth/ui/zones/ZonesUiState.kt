package com.myhealth.ui.zones

import com.myhealth.domain.engine.load.HrBounds
import com.myhealth.domain.engine.load.HrZone
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.engine.load.PolarisationSplit
import com.myhealth.domain.engine.load.SessionZoneTargets
import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.engine.running.DanielsPace
import com.myhealth.domain.engine.running.DanielsPaces
import com.myhealth.domain.engine.running.PaceConfidence
import com.myhealth.domain.engine.running.PaceZoneBand
import com.myhealth.domain.engine.running.PaceZoneEngine
import com.myhealth.domain.engine.suggest.IntervalStructures
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.SessionType
import com.myhealth.ui.training.formatPaceSecPerKm
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.HealthRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

/**
 * ViewModel state for [ZonesScreen] (PLAN §4.2 "Zones & paces", P14.6): the five HR zones, the
 * per-zone pace band, the Daniels paces from the current VDOT, the 28-day polarisation split and
 * the per-session-type target table.
 */
data class ZonesUiState(
    val isLoading: Boolean = true,
    val model: HrZoneModel? = null,
    val bands: List<PaceZoneBand> = emptyList(),
    val vdot: Double? = null,
    val polarisation: PolarisationSplit? = null,
) {
    /** E/M/T/I/R in that order; empty without a VDOT. */
    val danielsPaces: Map<DanielsPace, Int> get() = vdot?.let { DanielsPaces.table(it) }.orEmpty()

    val sessionRows: List<SessionZoneRow> get() = sessionZoneRows(model, bands)

    /**
     * Whether there is anything at all to show — gates the empty state (`zui08`). A zone model
     * alone is enough: the five zones follow from the profile (age formula / manual bounds) and
     * are useful before the first run; only the pace rows then say "not enough data yet".
     */
    val hasAnyData: Boolean
        get() = model != null ||
            vdot != null ||
            bands.any { it.confidence != PaceConfidence.NONE } ||
            (polarisation?.totalMinutes ?: 0.0) > 0.0

    val isEmpty: Boolean get() = !isLoading && !hasAnyData
}

/**
 * "Z4 · Threshold · 162–175 bpm", or the open-ended "Z5 · VO2max · 176+ bpm" (`zui01`, `zui02`).
 * [name] is the already-resolved zone name (`hr_zone_N_name`) — this function stays pure so it is
 * unit-testable without a `stringResource`.
 */
fun zoneRowLabel(zone: HrZone, name: String): String =
    "Z${zone.index} · $name · ${bpmRangeText(zone)}"

/** "162–175 bpm" / "176+ bpm" — the bpm half of a zone label, shared by the row and the chip. */
fun bpmRangeText(zone: HrZone): String =
    zone.highBpm?.let { "${zone.lowBpm}–$it bpm" } ?: "${zone.lowBpm}+ bpm"

/** "4:09–4:22 /km"; `null` when the band carries no range at all (`zui03`). */
fun paceBandLabel(band: PaceZoneBand): String? {
    val low = band.lowSecPerKm ?: return null
    val high = band.highSecPerKm ?: return null
    return "${IntervalStructures.mmss(low)}–${IntervalStructures.mmss(high)} /km"
}

/**
 * Which confidence sentence a [PaceConfidence] resolves to (`zui04`): "measured from N runs" /
 * "partly modelled" (both `MEDIUM` and `LOW` — the blend differs, the sentence does not) /
 * "modelled from VDOT" / "not enough data yet". The sentences themselves are string resources
 * resolved by the screen, so this stays a pure, testable mapping.
 */
enum class ConfidenceMessageKind { MEASURED, PARTLY_MODELLED, MODELLED, NOT_ENOUGH_DATA }

fun confidenceMessageKind(confidence: PaceConfidence): ConfidenceMessageKind = when (confidence) {
    PaceConfidence.HIGH -> ConfidenceMessageKind.MEASURED
    PaceConfidence.MEDIUM, PaceConfidence.LOW -> ConfidenceMessageKind.PARTLY_MODELLED
    PaceConfidence.MODELLED -> ConfidenceMessageKind.MODELLED
    PaceConfidence.NONE -> ConfidenceMessageKind.NOT_ENOUGH_DATA
}

/** Whole-number easy/moderate/hard percentages of a [PolarisationSplit], half-up (`zui05`). */
fun easyPercent(split: PolarisationSplit): Int = TrimpDefaults.roundHalfUp(split.easyShare * 100)

fun hardPercent(split: PolarisationSplit): Int = TrimpDefaults.roundHalfUp(split.hardShare * 100)

fun moderatePercent(split: PolarisationSplit): Int =
    (100 - easyPercent(split) - hardPercent(split)).coerceAtLeast(0)

/** §3.9: less than 70 % easy time over the last 28 days is not a polarised week. */
fun showsPolarisationHint(split: PolarisationSplit): Boolean =
    split.totalMinutes > 0.0 && split.easyShare < 0.70

/**
 * The single zone a session type's prescription is read at — the first zone of its target range
 * (§3.9), except `INTERVAL_RUN` and `TEMPO_RUN`, which are read at the top of their range. Mirrors
 * [PaceZoneEngine.recommendedPaceFor]'s own zone choice so the chip and the pace always agree.
 * `null` when the session type has no target at all (strength, soccer, rest).
 */
fun targetZoneIndexFor(sessionType: SessionType): Int? {
    val target = SessionZoneTargets.targetFor(sessionType) ?: return null
    return when (sessionType) {
        SessionType.INTERVAL_RUN, SessionType.TEMPO_RUN -> target.last
        else -> target.first
    }
}

/**
 * "Z2 · 134–147 bpm" for the plan-UI chip (§4.1/§4.2); `null` when the session type has no target
 * or the zone model has not resolved yet.
 */
fun zoneChipLabel(sessionType: SessionType, model: HrZoneModel?): String? {
    val idx = targetZoneIndexFor(sessionType) ?: return null
    val zone = model?.zones?.firstOrNull { it.index == idx } ?: return null
    return "Z${zone.index} · ${bpmRangeText(zone)}"
}

/** One row of the Zones & paces per-session-type table; both fields `null` means "n/a" (`zui06`). */
data class SessionZoneRow(val sessionType: SessionType, val zoneLabel: String?, val paceLabel: String?)

/** One row per [SessionType] in declaration order (`zui06`). */
fun sessionZoneRows(model: HrZoneModel?, bands: List<PaceZoneBand>): List<SessionZoneRow> =
    SessionType.entries.map { type ->
        val idx = targetZoneIndexFor(type)
        val zone = idx?.let { i -> model?.zones?.firstOrNull { it.index == i } }
        val zoneLabel = zone?.let { "Z${it.index} · ${bpmRangeText(it)}" }
        val paceLabel = PaceZoneEngine.recommendedPaceFor(type, bands)?.centreSecPerKm?.let(::formatPaceSecPerKm)
        SessionZoneRow(type, zoneLabel, paceLabel)
    }

/**
 * A profile-only zone model (no activity history) for the four extended plan-UI rows of §4.2,
 * which only need a zone's bpm range, not a measured pace band — the full history-based
 * resolution stays in [ZonesViewModel] alone, which is where the accuracy actually matters (§3.10).
 */
fun lightweightHrZoneModel(
    profile: Profile?,
    today: LocalDate,
    /** NOTE-20: the last days' resting-HR readings, so a chip's bpm range matches the Zones screen. */
    restingHrLast7Days: List<Int> = emptyList(),
): HrZoneModel? =
    profile?.let { HrZoneModel.resolve(it, HrBounds.compute(it, today, restingHrLast7Days = restingHrLast7Days)) }

/**
 * NOTE-21: the zone model every chip and the Zones screen agree on — the profile's manual values,
 * the last [TrimpDefaults.REST_HR_WINDOW_DAYS] days of resting HR and the highest HR observed on any
 * activity of the last [TrimpDefaults.OBSERVED_MAX_HR_WINDOW_DAYS] days, exactly what `HrBounds`
 * (§3.2.1) is defined on.
 */
suspend fun resolveHrZoneModel(
    profile: Profile?,
    todayDay: Long,
    healthRepo: HealthRepository,
    activityRepo: ActivityRepository,
): HrZoneModel? {
    if (profile == null) return null
    val restingHr = healthRepo
        .observeRange(todayDay - TrimpDefaults.REST_HR_WINDOW_DAYS + 1, todayDay)
        .first()
        .mapNotNull { it.restingHr }
    val observedMax = activityRepo
        .observeRange(todayDay - TrimpDefaults.OBSERVED_MAX_HR_WINDOW_DAYS + 1, todayDay)
        .first()
        .mapNotNull { it.maxHr }
        .maxOrNull()
    val bounds = HrBounds.compute(
        profile = profile,
        on = LocalDate.fromEpochDays(todayDay),
        restingHrLast7Days = restingHr,
        observedMaxHrLast365d = observedMax,
    )
    return HrZoneModel.resolve(profile, bounds)
}
