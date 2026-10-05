package com.myhealth.domain.engine.bike

import com.myhealth.domain.model.SportType
import kotlin.math.floor

/**
 * Every constant and shared convention of the cycling engines (PLAN §3.8, P12.2) in one place, so
 * the safety bounds can be reviewed on their own — the cycling mirror of
 * [com.myhealth.domain.engine.load.TrimpDefaults].
 *
 * Rounding is **half-up** everywhere (amendment A4): watts and seconds are whole numbers by the
 * time they reach `ride_best`, `profile.ftpWattsManual` or the TSS formula.
 */
object BikeDefaults {

    /** Sports whose efforts may become a `ride_best` row or feed the FTP estimate. */
    val ELIGIBLE_SPORTS: Set<SportType> = setOf(SportType.CYCLING, SportType.CYCLING_INDOOR)

    // ---- FTP estimate (§3.8.1) -------------------------------------------------------------------

    /** Both estimated rungs only look this far back; older efforts say nothing about today's FTP. */
    const val FTP_WINDOW_DAYS: Long = 90L

    /** Coggan's `FTP = 0.95 × best 20-minute power`, reused for the session-NP rung. */
    const val FTP_FACTOR: Double = 0.95

    /** A session-NP estimate needs a ride at least this long to be a threshold effort, not a burst. */
    const val FTP_MIN_RIDE_SEC: Int = 40 * 60

    /** Sanity window for the finished estimate; outside it the estimate is `null`, not clamped. */
    const val FTP_MIN_W: Int = 50
    const val FTP_MAX_W: Int = 600

    // ---- power bests (§3.8.2) --------------------------------------------------------------------

    /** A gap longer than this is a pause, not a sample interval; it is capped (same as `PowerMath`). */
    const val MAX_GAP_SEC: Int = 60

    /** Samples further apart than this only yield an estimated best (same gate as §3.4's splits). */
    const val MAX_TRUSTED_INTERVAL_SEC: Double = 10.0

    /** Sanity window for a `POWER_*` best: below is soft-pedalling, above is a broken meter. */
    const val POWER_MIN_W: Double = 30.0
    const val POWER_MAX_W: Double = 1_500.0

    // ---- time bests (§3.8.3) ---------------------------------------------------------------------

    /** `|total - D| <= max(1 % of D, 50 m)` for the full-ride method (mirrors §3.4 method A). */
    const val FULL_RIDE_TOLERANCE_SHARE: Double = 0.01
    const val FULL_RIDE_TOLERANCE_M: Double = 50.0

    /** Beyond this much scaling the full-ride time is flagged estimated. */
    const val FULL_RIDE_EXACT_M: Double = 5.0

    /** Sanity window for a `TIME_*` best in km/h: a walk pace and a downhill sprint. */
    const val MIN_SPEED_KMH: Double = 8.0
    const val MAX_SPEED_KMH: Double = 70.0

    // ---- shared arithmetic -----------------------------------------------------------------------

    /** Half-up rounding to the nearest integer (amendment A4). */
    fun roundHalfUp(value: Double): Int = floor(value + 0.5).toInt()

    fun isEligible(sportType: SportType): Boolean = sportType in ELIGIBLE_SPORTS

    /** km/h of covering [meters] in [seconds]. */
    fun speedKmh(meters: Double, seconds: Double): Double =
        if (seconds <= 0.0) 0.0 else meters / seconds * 3.6
}
