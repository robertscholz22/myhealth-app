package com.myhealth.ui.activities

import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.util.epochDayDate
import com.myhealth.ui.common.fmtKm
import com.myhealth.ui.common.usText
import com.myhealth.ui.common.zeroPad
import kotlin.math.roundToInt
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

/** One month header + the activities that fall in it, in the order they arrived (§4.2 Activities). */
data class MonthGroup(val label: String, val items: List<ActivitySummary>)

/** ViewModel state for [ActivitiesScreen] (PLAN §4.2 Activities, P2.9). */
data class ActivitiesUiState(
    val isLoading: Boolean = true,
    val filter: SportGroup? = null,
    val groups: List<MonthGroup> = emptyList(),
    /** P8.6: drives the pull-to-refresh spinner — the same "Sync now" work the empty state runs. */
    val isSyncing: Boolean = false,
    /** P8.6: the last sync failure, shown as a retryable error banner above the list. */
    val syncError: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && groups.all { it.items.isEmpty() }
}

private const val MONTH_LABEL_FORMAT = "MMMM yyyy"

/**
 * Groups activities into calendar-month buckets, preserving the incoming order within and across
 * groups (the caller sorts reverse-chronologically first, so months come out most-recent-first
 * too). Pure — unit-tested in `ActivitiesUiStateTest`.
 */
fun List<ActivitySummary>.groupByMonth(): List<MonthGroup> {
    val byMonth = LinkedHashMap<YearMonth, MutableList<ActivitySummary>>()
    for (item in this) {
        val month = item.day.epochDayDate().yearMonth
        byMonth.getOrPut(month) { mutableListOf() }.add(item)
    }
    return byMonth.map { (month, items) -> MonthGroup(month.firstDay.usText(MONTH_LABEL_FORMAT), items) }
}

/**
 * `m:ss /km` pace from a speed in m/s (used for runs); `null` when there is no speed to convert
 * (e.g. a strength session, or a zero/negative reading). Pure — unit-tested.
 */
fun formatPaceMinPerKm(avgSpeedMps: Double?): String? {
    if (avgSpeedMps == null || avgSpeedMps <= 0.0) return null
    val secPerKm = (1000.0 / avgSpeedMps).roundToInt()
    val min = secPerKm / 60
    val sec = secPerKm % 60
    return "$min:${sec.zeroPad(2)} /km"
}

/** Distance in km, 2 decimals (§4.2 Activities row spec); `null` when the activity has no distance. */
fun formatDistanceKm(distanceMeters: Double?): String? =
    distanceMeters?.let { fmtKm(it / 1000.0) }

/** `"1h 05m"` / `"45 min"` — used on both the list row and the detail header. */
fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m.zeroPad(2)}m" else "$m min"
}
