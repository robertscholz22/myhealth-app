package com.myhealth.domain.engine.bike

import com.myhealth.domain.engine.bike.BikeDefaults as D
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.SportType

/** Which rung of [FtpEstimator]'s ladder produced an [FtpEstimate] (PLAN §3.8.1). */
enum class FtpSource {
    /** `profile.ftpWattsManual` — the owner's own number, always wins. */
    MANUAL,

    /** `0.95 × best POWER_20MIN` of the last 90 days, i.e. an actual 20-minute effort. */
    STREAM_20MIN,

    /** `0.95 × best session NP` of a ride of at least 40 minutes — the weakest rung. */
    SESSION_NP,
}

/**
 * A functional-threshold-power estimate in whole watts, plus where it came from. [basisActivityId]
 * and [basisDay] point at the effort it was derived from (`null` for [FtpSource.MANUAL]) so the UI
 * can link to that ride.
 */
data class FtpEstimate(
    val watts: Int,
    val source: FtpSource,
    val basisActivityId: Long? = null,
    val basisDay: Long? = null,
) {
    /** True for the rung the plan calls "the weakest": a whole-session average, not a real test. */
    val isEstimatedFromSession: Boolean get() = source == FtpSource.SESSION_NP
}

/**
 * One ride's inputs for the session-NP rung. Deliberately a tiny value type rather than
 * [ActivitySession]/[ActivitySummary] so the estimator can be driven from either shape (and from a
 * test) — see the [of] overloads.
 */
data class RidePower(
    val activityId: Long?,
    val day: Long,
    val durationSec: Int,
    val sportType: SportType,
    val avgPowerW: Int?,
    val normalizedPowerW: Int?,
) {
    /** `normalizedPowerW ?: avgPowerW`, the plan's order of preference. */
    val powerW: Int? get() = normalizedPowerW ?: avgPowerW

    companion object {
        fun of(session: ActivitySession): RidePower = RidePower(
            activityId = session.id,
            day = session.day,
            durationSec = session.durationSec,
            sportType = session.sportType,
            avgPowerW = session.avgPowerW,
            normalizedPowerW = session.normalizedPowerW,
        )

        fun of(summary: ActivitySummary): RidePower = RidePower(
            activityId = summary.id,
            day = summary.day,
            durationSec = summary.durationSec,
            sportType = summary.sportType,
            avgPowerW = summary.avgPowerW,
            normalizedPowerW = summary.normalizedPowerW,
        )
    }
}

/**
 * FTP estimate (PLAN §3.8.1), first match wins:
 *
 * 1. `profile.ftpWattsManual` → [FtpSource.MANUAL];
 * 2. `0.95 × max(POWER_20MIN)` over `ride_best` rows no older than
 *    [BikeDefaults.FTP_WINDOW_DAYS] → [FtpSource.STREAM_20MIN];
 * 3. `0.95 × max(normalizedPowerW ?: avgPowerW)` over rides of an eligible cycling sport that are
 *    at least [BikeDefaults.FTP_MIN_RIDE_SEC] long and no older than the same window →
 *    [FtpSource.SESSION_NP].
 *
 * Watts are rounded half-up. A result outside `[50, 600]` W is **`null`**, not clamped: a number
 * that far off means the input was junk (a mis-scaled trainer, a 5-minute "20-minute" best), and a
 * clamped 50 W would silently drive the TSS of every ride.
 *
 * The estimate is deliberately **not cached**: `LoadRecomputeService` resolves it once per run,
 * after refreshing `ride_best`, and the UI recomputes it from flows. Nothing persists it.
 */
object FtpEstimator {

    fun estimate(
        manualWatts: Int?,
        powerBests: List<RideBest>,
        rides: List<RidePower>,
        todayDay: Long,
    ): FtpEstimate? {
        manualWatts?.let { manual ->
            return sane(FtpEstimate(watts = manual, source = FtpSource.MANUAL))
        }
        twentyMinute(powerBests, todayDay)?.let { return it }
        return sessionNp(rides, todayDay)
    }

    /** The same ladder off whole activities — the shape the repositories hand out. */
    fun estimateFromSummaries(
        manualWatts: Int?,
        powerBests: List<RideBest>,
        rides: List<ActivitySummary>,
        todayDay: Long,
    ): FtpEstimate? = estimate(manualWatts, powerBests, rides.map { RidePower.of(it) }, todayDay)

    /** The same ladder off full sessions — what `LoadRecomputeService` already has in memory. */
    fun estimateFromSessions(
        manualWatts: Int?,
        powerBests: List<RideBest>,
        rides: List<ActivitySession>,
        todayDay: Long,
    ): FtpEstimate? = estimate(manualWatts, powerBests, rides.map { RidePower.of(it) }, todayDay)

    private fun twentyMinute(powerBests: List<RideBest>, todayDay: Long): FtpEstimate? {
        val best = powerBests
            .filter { it.kind == RideBestKind.POWER_20MIN && inWindow(it.day, todayDay) && it.value > 0.0 }
            .maxByOrNull { it.value }
            ?: return null
        return sane(
            FtpEstimate(
                watts = D.roundHalfUp(D.FTP_FACTOR * best.value),
                source = FtpSource.STREAM_20MIN,
                basisActivityId = best.activityId,
                basisDay = best.day,
            ),
        )
    }

    private fun sessionNp(rides: List<RidePower>, todayDay: Long): FtpEstimate? {
        val best = rides
            .filter { D.isEligible(it.sportType) }
            .filter { it.durationSec >= D.FTP_MIN_RIDE_SEC && inWindow(it.day, todayDay) }
            .filter { (it.powerW ?: 0) > 0 }
            .maxByOrNull { it.powerW ?: 0 }
            ?: return null
        return sane(
            FtpEstimate(
                watts = D.roundHalfUp(D.FTP_FACTOR * (best.powerW ?: 0)),
                source = FtpSource.SESSION_NP,
                basisActivityId = best.activityId,
                basisDay = best.day,
            ),
        )
    }

    /** `day` is inside the look-back window ending today (a day exactly 90 days old still counts). */
    private fun inWindow(day: Long, todayDay: Long): Boolean = day >= todayDay - D.FTP_WINDOW_DAYS

    private fun sane(estimate: FtpEstimate): FtpEstimate? =
        estimate.takeIf { it.watts in D.FTP_MIN_W..D.FTP_MAX_W }
}
