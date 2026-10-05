package com.myhealth.ui.bike

import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.bike.FtpSource
import com.myhealth.domain.engine.goal.GoalProgress
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.ui.common.usText
import kotlinx.datetime.LocalDate

/**
 * ViewModel state for [BikeScreen] (PLAN "UI.", P12.4): the FTP card, and the power/time PR
 * tables, one row per [RideBestKind] the athlete has an effort for.
 */
data class BikeUiState(
    val isLoading: Boolean = true,
    val ftp: FtpEstimate? = null,
    val powerBests: List<RideBest> = emptyList(),
    val timeBests: List<RideBest> = emptyList(),
    val indoorTrainerAvailable: Boolean = false,
)

/**
 * The current PR for every kind an athlete has an effort for: `MAX(value)` for the `POWER_*`
 * kinds, `MIN(value)` for the `TIME_*` ones (mirrors `RideBestDao.observeBestPerKind`'s SQL — kept
 * here too as a defensive, unit-testable reduction rather than trusting every caller to have
 * already deduplicated).
 */
fun List<RideBest>.bestPerKind(): List<RideBest> =
    groupBy { it.kind }.values.map { efforts ->
        if (efforts.first().kind.isPower) efforts.maxBy { it.value } else efforts.minBy { it.value }
    }

/** The [RideBestKind.isPower] rows, fastest window first (5 / 20 / 60 min). */
fun List<RideBest>.powerBests(): List<RideBest> =
    bestPerKind().filter { it.kind.isPower }.sortedBy { it.kind.windowSec ?: 0 }

/** The non-power rows, shortest distance first (10 / 20 / 40 / 100 km). */
fun List<RideBest>.timeBests(): List<RideBest> =
    bestPerKind().filter { !it.kind.isPower }.sortedBy { it.kind.distanceMeters ?: 0.0 }

/** "5 min power" / "20 km" — the Bike screen's row label for one [RideBestKind]. */
fun rideBestKindLabel(kind: RideBestKind): String = when (kind) {
    RideBestKind.POWER_5MIN -> "5 min power"
    RideBestKind.POWER_20MIN -> "20 min power"
    RideBestKind.POWER_60MIN -> "60 min power"
    RideBestKind.TIME_10K -> "10 km"
    RideBestKind.TIME_20K -> "20 km"
    RideBestKind.TIME_40K -> "40 km"
    RideBestKind.TIME_100K -> "100 km"
}

/** "247 W" for a power best, `mm:ss` (or `h:mm:ss`) for a time best. */
fun formatRideBestValue(best: RideBest): String =
    if (best.kind.isPower) "${best.value.toInt()} W" else GoalProgress.formatTime(best.value.toInt())

/** "Manual" / "20-minute best" / "Ride average power" — [FtpSource] in plain words (P12.4). */
fun FtpSource.label(): String = when (this) {
    FtpSource.MANUAL -> "Manual"
    FtpSource.STREAM_20MIN -> "20-minute best"
    FtpSource.SESSION_NP -> "Ride average power"
}

/** `d MMM`, e.g. "2 Sep" — the FTP card's and PR table's basis date. */
fun formatShortDate(epochDay: Long): String =
    LocalDate.fromEpochDays(epochDay).usText("d MMM")
