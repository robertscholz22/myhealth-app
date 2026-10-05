package com.myhealth.ui.common.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.fmtDecimal
import com.myhealth.ui.theme.MyHealthTheme
import kotlin.math.max
import kotlin.math.min

/** A series is drawn with dots as well as a line while it is sparse enough for them to read. */
private const val DOT_THRESHOLD = 40

/**
 * Multi-series line chart in a [SectionCard] (PLAN P8.2, amendment A2 — Compose `Canvas`, no chart
 * library). Handles gaps (`null`/NaN y), an optional horizontal [goalLine] and shaded y [bands]
 * (the ACWR risk zones of §3.2.3). All colours come from `MaterialTheme.colorScheme`.
 */
@Composable
fun LineChartCard(
    title: String,
    series: List<ChartSeries>,
    xLabels: List<String>,
    yFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    goalLine: Double? = null,
    bands: List<ChartBand> = emptyList(),
    height: Dp = ChartHeight,
    emptyMessage: String = stringResource(Res.string.chart_common_no_data),
    /** Forces the legend on for a single-series chart whose title does not name the series. */
    alwaysShowLegend: Boolean = false,
    action: @Composable (() -> Unit)? = null,
) {
    SectionCard(title = title, modifier = modifier, action = action) {
        val drawable = series.filter { s -> s.points.any { it.y.isPlottable() } }
        if (drawable.isEmpty()) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        if (drawable.size > 1 || alwaysShowLegend) {
            ChartLegend(drawable, modifier = Modifier.fillMaxWidth())
        }
        val measurer = rememberTextMeasurer()
        val labelStyle = chartLabelStyle()
        val gridColor = chartGridColor()
        val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
        val colors = drawable.indices.map { chartSeriesColor(it) }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(top = 4.dp),
        ) {
            drawLineChart(
                series = drawable,
                colors = colors,
                xLabels = xLabels,
                yFormatter = yFormatter,
                goalLine = goalLine,
                goalColor = goalColor,
                bands = bands,
                measurer = measurer,
                labelStyle = labelStyle,
                gridColor = gridColor,
            )
        }
    }
}

private fun Double?.isPlottable(): Boolean = this != null && this.isFinite()

@Suppress("LongParameterList")
private fun DrawScope.drawLineChart(
    series: List<ChartSeries>,
    colors: List<Color>,
    xLabels: List<String>,
    yFormatter: (Double) -> String,
    goalLine: Double?,
    goalColor: Color,
    bands: List<ChartBand>,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    gridColor: Color,
) {
    val values = series.flatMap { s -> s.points.mapNotNull { it.y } }.filter { it.isFinite() }
    if (values.isEmpty()) return
    val lowest = min(values.min(), goalLine?.takeIf { it.isFinite() } ?: values.min())
    val highest = max(values.max(), goalLine?.takeIf { it.isFinite() } ?: values.max())
    val range = ChartScale.niceRange(lowest, highest)
    val gap = 6.dp.toPx()
    val plot = layoutPlot(
        canvasWidth = size.width,
        canvasHeight = size.height,
        yLabels = range.ticks.map(yFormatter),
        hasXLabels = xLabels.isNotEmpty(),
        measurer = measurer,
        labelStyle = labelStyle,
        gapPx = gap,
        topInsetPx = 0f,
    )
    if (plot.width <= 0f || plot.height <= 0f) return

    val xs = series.flatMap { s -> s.points.map { it.x } }.filter { it.isFinite() }
    val xMin = xs.min()
    val xMax = xs.max()

    drawBands(bands, plot, range)
    drawYAxis(plot, range, yFormatter, measurer, labelStyle, gridColor, gap)
    if (goalLine != null && goalLine.isFinite()) {
        val y = plot.top + ChartScale.toY(goalLine, range, plot.height)
        drawLine(
            color = goalColor,
            start = Offset(plot.left, y),
            end = Offset(plot.right, y),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
    }
    series.forEachIndexed { index, item ->
        drawSeries(item, colors[index], plot, range, xMin, xMax)
    }
    drawXAxis(plot, xLabels, measurer, labelStyle, gap)
}

private fun DrawScope.drawBands(bands: List<ChartBand>, plot: Plot, range: NiceRange) {
    if (bands.isEmpty()) return
    clipRect(plot.left, plot.top, plot.right, plot.bottom) {
        bands.forEach { band ->
            val low = max(band.from, range.min)
            val high = min(band.to, range.max)
            if (high <= low) return@forEach
            val yTop = plot.top + ChartScale.toY(high, range, plot.height)
            val yBottom = plot.top + ChartScale.toY(low, range, plot.height)
            drawRect(
                color = band.color,
                topLeft = Offset(plot.left, yTop),
                size = Size(plot.width, yBottom - yTop),
            )
        }
    }
}

private fun DrawScope.drawSeries(
    series: ChartSeries,
    color: Color,
    plot: Plot,
    range: NiceRange,
    xMin: Double,
    xMax: Double,
) {
    val path = Path()
    val dots = mutableListOf<Offset>()
    var open = false
    series.points.forEach { point ->
        val y = point.y
        if (y == null || !y.isFinite() || !point.x.isFinite()) {
            open = false
            return@forEach
        }
        val px = plot.left + ChartScale.toX(point.x, xMin, xMax, plot.width)
        val py = plot.top + ChartScale.toY(y, range, plot.height)
        if (open) path.lineTo(px, py) else path.moveTo(px, py)
        open = true
        dots += Offset(px, py)
    }
    if (dots.isEmpty()) return
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = 2.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = if (series.dashed) {
                PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            } else {
                null
            },
        ),
    )
    if (dots.size < DOT_THRESHOLD) {
        dots.forEach { drawCircle(color = color, radius = 2.5.dp.toPx(), center = it) }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun LineChartCardPreview() {
    MyHealthTheme(dynamicColor = false) {
        LineChartCard(
            title = "Weight",
            series = listOf(
                ChartSeries("Weight", (0..20).map { ChartPoint(it.toDouble(), 82.0 - it * 0.2) }),
                ChartSeries(
                    name = "7-day average",
                    points = (0..20).map { ChartPoint(it.toDouble(), 82.2 - it * 0.18) },
                    dashed = true,
                ),
            ),
            xLabels = listOf("Jul 1", "Jul 11", "Jul 21"),
            yFormatter = { fmtDecimal(it, 1) },
            goalLine = 76.0,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 380, name = "Bands and gaps")
@Composable
private fun LineChartCardBandsPreview() {
    MyHealthTheme(dynamicColor = false) {
        LineChartCard(
            title = "ACWR",
            series = listOf(
                ChartSeries(
                    name = "ACWR",
                    points = (0..20).map {
                        ChartPoint(it.toDouble(), if (it == 8) null else 0.7 + it * 0.05)
                    },
                ),
            ),
            xLabels = listOf("Aug 1", "Aug 21"),
            yFormatter = { fmtDecimal(it, 1) },
            bands = listOf(
                ChartBand("Optimal", 0.8, 1.3, Color(0x332E7D32)),
                ChartBand("Caution", 1.3, 1.5, Color(0x33F9A825)),
                ChartBand("High risk", 1.5, Double.MAX_VALUE, Color(0x33BA1A1A)),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 380, name = "Empty")
@Composable
private fun LineChartCardEmptyPreview() {
    MyHealthTheme(dynamicColor = false) {
        LineChartCard(
            title = "Resting heart rate",
            series = emptyList(),
            xLabels = emptyList(),
            yFormatter = { fmtDecimal(it, 0) },
            modifier = Modifier.padding(16.dp),
        )
    }
}
