package com.myhealth.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.CalendarDay
import com.myhealth.ui.common.mathRound
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

/** The four marker dots a day cell can show, in render order (PLAN §4.2 Calendar). */
enum class DayMarker { EVENT, PLANNED, ACTIVITY, MEAL }

/** Colour band of the kcal-delta bar: intake − target ≤ 0 green, ≤ +300 amber, above that red. */
enum class KcalDeltaLevel { UNDER, NEAR, OVER }

/** The thin bar under a day cell: how full the day is vs its target, and by how much it is over. */
data class KcalDeltaBar(val level: KcalDeltaLevel, val fraction: Float, val deltaKcal: Int)

/** Above this many kcal over target the bar turns red (§4.2 Calendar). */
private const val OVER_TARGET_RED_KCAL = 300.0

/**
 * Markers for one day, at most one dot per kind and never more than four. Pure — unit-tested in
 * `DayCellMarkersTest`.
 */
fun dayMarkers(day: CalendarDay?): List<DayMarker> {
    if (day == null) return emptyList()
    return buildList {
        if (day.events.isNotEmpty()) add(DayMarker.EVENT)
        if (day.planned.isNotEmpty()) add(DayMarker.PLANNED)
        if (day.activities.isNotEmpty()) add(DayMarker.ACTIVITY)
        if (day.meals.isNotEmpty()) add(DayMarker.MEAL)
    }
}

/**
 * The kcal-delta bar, or `null` when either side is unknown — no target snapshot for the day, or
 * nothing logged yet. Pure — unit-tested in `DayCellMarkersTest`.
 */
fun kcalDeltaBar(day: CalendarDay?): KcalDeltaBar? {
    val targetKcal = day?.target?.kcal?.toDouble() ?: return null
    if (targetKcal <= 0.0) return null
    val intakeKcal = day.intake.kcal
    if (intakeKcal <= 0.0) return null
    val delta = intakeKcal - targetKcal
    val level = when {
        delta <= 0.0 -> KcalDeltaLevel.UNDER
        delta <= OVER_TARGET_RED_KCAL -> KcalDeltaLevel.NEAR
        else -> KcalDeltaLevel.OVER
    }
    return KcalDeltaBar(
        level = level,
        fraction = (intakeKcal / targetKcal).coerceIn(0.0, 1.0).toFloat(),
        deltaKcal = mathRound(delta).toInt(),
    )
}

@Composable
internal fun DayMarker.color(): Color = when (this) {
    DayMarker.EVENT -> MaterialTheme.colorScheme.tertiary
    DayMarker.PLANNED -> MaterialTheme.colorScheme.secondary
    DayMarker.ACTIVITY -> MaterialTheme.colorScheme.primary
    DayMarker.MEAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun KcalDeltaLevel.color(): Color = when (this) {
    KcalDeltaLevel.UNDER -> MaterialTheme.colorScheme.primary
    KcalDeltaLevel.NEAR -> MaterialTheme.colorScheme.tertiary
    KcalDeltaLevel.OVER -> MaterialTheme.colorScheme.error
}

/** Semantics test tag on whichever dot renders a [CycleDayMarker] (PLAN §5 P11.3 instrumented
 * test): text alone cannot distinguish "today has a period logged" from any other cell. */
const val CYCLE_MARKER_TEST_TAG: String = "cycle_marker"

/** One cell of the month grid: day number, marker dots, kcal-delta bar, and — while the cycle
 * tracker is on — the P11.3 cycle dot ([cycleMarker], `null` for a day with no cycle data). */
@Composable
fun DayCell(
    date: LocalDate,
    day: CalendarDay?,
    isInAnchorMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cycleMarker: CycleDayMarker? = null,
) {
    val numberColor = when {
        isToday -> MaterialTheme.colorScheme.onPrimary
        isInAnchorMonth -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Column(
        modifier = modifier
            .height(56.dp)
            .then(
                if (isSelected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .then(
                    if (isToday) {
                        Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.day.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = numberColor,
            )
        }
        MarkerRow(dayMarkers(day))
        KcalDeltaStrip(kcalDeltaBar(day))
        cycleMarker?.let { CycleMarkerDot(it) }
    }
}

/** Solid dot for a logged period day, a lighter one for a predicted period day, a ring for
 * ovulation, a small dot for the fertile window (PLAN §5 P11.3). */
@Composable
internal fun CycleMarkerDot(marker: CycleDayMarker) {
    val color = MaterialTheme.colorScheme.error
    val tertiary = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .testTag(CYCLE_MARKER_TEST_TAG)
            .size(6.dp)
            .then(
                when (marker) {
                    CycleDayMarker.LOGGED_PERIOD -> Modifier.background(color, CircleShape)
                    CycleDayMarker.PREDICTED_PERIOD -> Modifier.background(color.copy(alpha = 0.35f), CircleShape)
                    CycleDayMarker.OVULATION -> Modifier.border(1.dp, tertiary, CircleShape)
                    CycleDayMarker.FERTILE -> Modifier.background(tertiary.copy(alpha = 0.5f), CircleShape)
                },
            ),
    )
}

@Composable
private fun MarkerRow(markers: List<DayMarker>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(6.dp),
    ) {
        markers.forEach { marker ->
            Box(modifier = Modifier.size(5.dp).background(marker.color(), CircleShape))
        }
    }
}

@Composable
private fun KcalDeltaStrip(bar: KcalDeltaBar?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(3.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)),
    ) {
        if (bar != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(bar.fraction.coerceAtLeast(0.08f))
                    .height(3.dp)
                    .background(bar.level.color(), RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DayCellPreview() {
    MyHealthTheme(dynamicColor = false) {
        Row {
            DayCell(
                date = LocalDate(2026, 9, 14),
                day = previewCalendarDay(LocalDate(2026, 9, 14).toEpochDays()),
                isInAnchorMonth = true,
                isToday = true,
                isSelected = false,
                onClick = {},
                modifier = Modifier.size(48.dp, 56.dp),
            )
            DayCell(
                date = LocalDate(2026, 9, 15),
                day = null,
                isInAnchorMonth = false,
                isToday = false,
                isSelected = true,
                onClick = {},
                modifier = Modifier.size(48.dp, 56.dp),
            )
        }
    }
}
