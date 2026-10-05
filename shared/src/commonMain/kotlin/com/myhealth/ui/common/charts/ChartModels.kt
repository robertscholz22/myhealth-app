package com.myhealth.ui.common.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One sample of a [ChartSeries]. A `null` or non-finite [y] is a **gap**: the line breaks there
 * instead of interpolating across missing data (a strap dropout is not a straight line).
 */
data class ChartPoint(val x: Double, val y: Double?)

/**
 * One line of a [LineChartCard]. [dashed] marks a derived line (a moving average, a goal) so it
 * reads as secondary next to the raw series without needing a second colour.
 */
data class ChartSeries(
    val name: String,
    val points: List<ChartPoint>,
    val dashed: Boolean = false,
)

/** A shaded horizontal y-band, e.g. one ACWR risk zone (§3.2.3). [to] may be `+inf` for "above". */
data class ChartBand(val label: String, val from: Double, val to: Double, val color: Color)

/** Fixed plot height used by both chart cards unless a caller overrides it. */
internal val ChartHeight = 180.dp

/** Axis/label text size — small enough that 5 y ticks never collide at 180 dp. */
internal val ChartLabelSize = 10.sp

/**
 * Series colours, in order: primary, tertiary, secondary, then the error red. The palette is read
 * from [MaterialTheme.colorScheme] only, so charts follow the green scheme (P8.6a) and dynamic
 * colour without any hard-coded hex.
 */
@Composable
@ReadOnlyComposable
fun chartSeriesColor(index: Int): Color {
    val scheme = MaterialTheme.colorScheme
    return when (index % 4) {
        0 -> scheme.primary
        1 -> scheme.tertiary
        2 -> scheme.secondary
        else -> scheme.error
    }
}

/** Grid, axis-label and goal-line colours — one place so both cards stay consistent. */
@Composable
@ReadOnlyComposable
internal fun chartGridColor(): Color = MaterialTheme.colorScheme.outlineVariant

@Composable
@ReadOnlyComposable
internal fun chartLabelStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = ChartLabelSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/** Legend chips, shown by [LineChartCard] as soon as there is more than one series. */
@Composable
internal fun ChartLegend(series: List<ChartSeries>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        series.forEachIndexed { index, item ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .size(width = 12.dp, height = 3.dp)
                        .background(chartSeriesColor(index), CircleShape),
                ) {}
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
