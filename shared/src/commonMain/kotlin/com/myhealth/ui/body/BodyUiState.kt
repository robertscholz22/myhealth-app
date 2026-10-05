package com.myhealth.ui.body

import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.DailyHealthSummary
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.util.epochDayDate
import com.myhealth.domain.util.minusDays
import com.myhealth.resources.*
import com.myhealth.ui.common.fmtKg
import com.myhealth.ui.common.fmtPercent
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource

/** The three history windows the Body charts offer (PLAN P8.3). */
enum class BodyRange(val days: Long, val labelRes: StringResource) {
    D30(30, Res.string.body_range_30d),
    D90(90, Res.string.body_range_90d),
    D365(365, Res.string.body_range_365d),
}

/**
 * ViewModel state for [BodyScreen] (§4.2 Body & Health: latest weight card, goal delta,
 * "Log weight" dialog, the weight/body-fat/resting-HR/sleep charts of P8.3 and the history list).
 */
data class BodyUiState(
    val isLoading: Boolean = true,
    val goalWeightKg: Double? = null,
    val range: BodyRange = BodyRange.D90,
    /** The selected window, most recent first — see [sortedByRecencyDescending]. */
    val measurements: List<BodyMeasurement> = emptyList(),
    /** Daily summaries over the selected window — the resting-HR chart's source. */
    val health: List<DailyHealthSummary> = emptyList(),
    /** The last 14 nights, for the sleep-duration bars. */
    val sleep: List<SleepRecord> = emptyList(),
    /** Today as an epoch day, so the charts' x domain does not depend on the data. */
    val today: Long = 0L,
    val showLogDialog: Boolean = false,
) {
    val fromDay: Long get() = today - range.days + 1
    val sleepFromNight: Long get() = today - SLEEP_BAR_NIGHTS + 1

    val latest: BodyMeasurement? get() = measurements.firstOrNull()

    val deltaToGoalKg: Double? get() = weightDeltaToGoalKg(latest?.weightKg, goalWeightKg)
}

/**
 * `latestWeightKg - goalWeightKg`: positive = above goal, negative = below goal, `null` when
 * either input is missing. Pure — unit-tested in `BodyUiStateTest`.
 */
fun weightDeltaToGoalKg(latestWeightKg: Double?, goalWeightKg: Double?): Double? =
    if (latestWeightKg == null || goalWeightKg == null) null else latestWeightKg - goalWeightKg

/** Most recent first, by [BodyMeasurement.measuredAtMillis]. Pure — unit-tested. */
fun List<BodyMeasurement>.sortedByRecencyDescending(): List<BodyMeasurement> =
    sortedByDescending { it.measuredAtMillis }

/** Keeps only measurements whose [BodyMeasurement.day] falls within the last [days] days of [today] (inclusive). */
fun List<BodyMeasurement>.withinLastDays(days: Long, today: LocalDate): List<BodyMeasurement> {
    val earliest = today.minusDays(days - 1)
    return filter { measurement ->
        val day = measurement.day.epochDayDate()
        day >= earliest && day <= today
    }
}

/**
 * The measurement list's value line (POLISH-5). A row that only carries a body-fat reading used
 * to render as "—, 17.4% fat"; it now reads "Body fat 17.4 %". Pure — unit-tested.
 */
fun measurementValueLabel(weightKg: Double?, bodyFatPercent: Double?): String = when {
    weightKg != null && bodyFatPercent != null ->
        "${fmtKg(weightKg)}, ${fmtPercent(bodyFatPercent)} fat"
    weightKg != null -> fmtKg(weightKg)
    bodyFatPercent != null -> "Body fat ${fmtPercent(bodyFatPercent)}"
    else -> "\u2014"
}
