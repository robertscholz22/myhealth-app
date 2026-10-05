package com.myhealth.domain.engine.load

import com.myhealth.domain.engine.load.TrimpDefaults as D
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.Profile
import com.myhealth.domain.util.EngineWarning
import kotlinx.datetime.LocalDate

/**
 * The heart-rate bounds every load computation is relative to (PLAN §3.2.1):
 *
 * ```
 * hrMax  = profile.maxHrManual ?: max(round(208 - 0.7*age), observedMaxHrLast365d ?: 0) ; clamp [150,220]
 * hrRest = median(restingHr over the last 7 days with data) ?: profile.restingHrManual ?: 60 ; clamp [30,90]
 * require(hrMax - hrRest >= 30) else hrMax = hrRest + 30
 * ```
 *
 * [hrr] is the heart-rate reserve fraction used by [TrimpCalculator] and by `timeInZones`, clamped
 * to `0..1` so a reading below rest or above max can never produce a negative or > 1 intensity.
 *
 * Interpretation choices (§3.2.1 is silent on both):
 * - the `[150,220]` clamp is applied to the **final** `hrMax`, i.e. also to a manual override — the
 *   clamp is a safety bound on the number the maths divides by, not on where it came from; a clamp
 *   that actually changed the value adds [EngineWarningCode.IMPLAUSIBLE_VALUE].
 * - the reserve guard lifts `hrMax` (as written), never lowers `hrRest`, and also warns.
 */
data class HrBounds(
    val hrMax: Int,
    val hrRest: Int,
    val warnings: List<EngineWarning> = emptyList(),
) {
    /** `hrMax - hrRest`, never below 1 so [hrr] cannot divide by zero. */
    val reserve: Int get() = (hrMax - hrRest).coerceAtLeast(1)

    /** Heart-rate reserve fraction of [hr], clamped to `0.0..1.0`. */
    fun hrr(hr: Double): Double = ((hr - hrRest) / reserve).coerceIn(0.0, 1.0)

    /** Heart-rate reserve fraction of [hr], clamped to `0.0..1.0`. */
    fun hrr(hr: Int): Double = hrr(hr.toDouble())

    companion object {

        /**
         * @param restingHrLast7Days every resting-HR reading of the last [D.REST_HR_WINDOW_DAYS]
         *   days that exists (the caller does the date filtering; missing days are simply absent).
         * @param observedMaxHrLast365d highest `maxHr` seen on an activity in the last 365 days.
         */
        fun compute(
            profile: Profile,
            on: LocalDate,
            restingHrLast7Days: List<Int> = emptyList(),
            observedMaxHrLast365d: Int? = null,
        ): HrBounds {
            val warnings = mutableListOf<EngineWarning>()

            val rawRest = D.median(restingHrLast7Days.map { it.toDouble() })?.let { D.roundHalfUp(it) }
                ?: profile.restingHrManual
                ?: run {
                    warnings += EngineWarning(
                        EngineWarningCode.MISSING_HR,
                        "No resting HR in the last ${D.REST_HR_WINDOW_DAYS} days and no manual " +
                            "value; assuming ${D.HR_REST_DEFAULT} bpm.",
                    )
                    D.HR_REST_DEFAULT
                }
            val hrRest = rawRest.coerceIn(D.HR_REST_MIN, D.HR_REST_MAX)
            if (hrRest != rawRest) {
                warnings += EngineWarning(
                    EngineWarningCode.IMPLAUSIBLE_VALUE,
                    "Resting HR $rawRest bpm clamped to $hrRest bpm.",
                )
            }

            val tanaka = D.roundHalfUp(D.TANAKA_INTERCEPT - D.TANAKA_SLOPE * profile.ageYears(on))
            val rawMax = profile.maxHrManual ?: maxOf(tanaka, observedMaxHrLast365d ?: 0)
            var hrMax = rawMax.coerceIn(D.HR_MAX_MIN, D.HR_MAX_MAX)
            if (hrMax != rawMax) {
                warnings += EngineWarning(
                    EngineWarningCode.IMPLAUSIBLE_VALUE,
                    "Max HR $rawMax bpm clamped to $hrMax bpm.",
                )
            }

            val lifted = liftedMaxHr(hrMax, hrRest)
            if (lifted != hrMax) {
                warnings += EngineWarning(
                    EngineWarningCode.IMPLAUSIBLE_VALUE,
                    "Max HR $hrMax bpm is less than ${D.MIN_HR_RESERVE} bpm above resting " +
                        "$hrRest bpm; using $lifted bpm.",
                )
                hrMax = lifted
            }

            return HrBounds(hrMax = hrMax, hrRest = hrRest, warnings = warnings)
        }

        /**
         * The `hrMax - hrRest >= 30` guard of §3.2.1. The `[150,220]` / `[30,90]` clamps already
         * guarantee a 60 bpm reserve, so [compute] never actually needs it — it is kept (and tested)
         * so that changing either clamp can never silently yield a degenerate reserve.
         */
        fun liftedMaxHr(hrMax: Int, hrRest: Int): Int = maxOf(hrMax, hrRest + D.MIN_HR_RESERVE)
    }
}
