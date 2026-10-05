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
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.fmtDecimal
import com.myhealth.ui.theme.MyHealthTheme
import kotlin.math.max
import kotlin.math.min

/** Above this many bars the per-bar value labels would overlap, so they are dropped. */
private const val VALUE_LABEL_MAX_BARS = 14

/**
 * Bar chart in a [SectionCard] (PLAN P8.2, amendment A2 — Compose `Canvas`, no chart library).
 * [highlightIndex] tints one bar (today, or the night being inspected); values are labelled above
 * the bars while there are at most [VALUE_LABEL_MAX_BARS] of them.
 */
@Composable
fun BarChartCard(
    title: String,
    values: List<Double>,
    xLabels: List<String>,
    yFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    highlightIndex: Int? = null,
    height: Dp = ChartHeight,
    emptyMessage: String = stringResource(Res.string.chart_common_no_data),
    action: @Composable (() -> Unit)? = null,
) {
    SectionCard(title = title, modifier = modifier, action = action) {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val measurer = rememberTextMeasurer()
        val labelStyle = chartLabelStyle()
        val gridColor = chartGridColor()
        val barColor = MaterialTheme.colorScheme.primary
        val highlightColor = MaterialTheme.colorScheme.tertiary

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(top = 4.dp),
        ) {
            drawBarChart(
                values = values,
                xLabels = xLabels,
                yFormatter = yFormatter,
                highlightIndex = highlightIndex,
                barColor = barColor,
                highlightColor = highlightColor,
                measurer = measurer,
                labelStyle = labelStyle,
                gridColor = gridColor,
            )
        }
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawBarChart(
    values: List<Double>,
    xLabels: List<String>,
    yFormatter: (Double) -> String,
    highlightIndex: Int?,
    barColor: Color,
    highlightColor: Color,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    gridColor: Color,
) {
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty()) return
    // Bars are read against zero, so the axis always includes it.
    val range = ChartScale.niceRange(min(0.0, finite.min()), max(0.0, finite.max()))
    val gap = 6.dp.toPx()
    val showValues = values.size <= VALUE_LABEL_MAX_BARS
    val labelHeight = measurer.measure("0", labelStyle).size.height.toFloat()
    val plot = layoutPlot(
        canvasWidth = size.width,
        canvasHeight = size.height,
        yLabels = range.ticks.map(yFormatter),
        hasXLabels = xLabels.isNotEmpty(),
        measurer = measurer,
        labelStyle = labelStyle,
        gapPx = gap,
        topInsetPx = if (showValues) labelHeight else 0f,
    )
    if (plot.width <= 0f || plot.height <= 0f) return

    drawYAxis(plot, range, yFormatter, measurer, labelStyle, gridColor, gap)

    val slot = plot.width / values.size
    val barWidth = (slot * 0.66f).coerceAtLeast(1f)
    val zeroY = plot.top + ChartScale.toY(0.0, range, plot.height)
    values.forEachIndexed { index, value ->
        if (!value.isFinite()) return@forEachIndexed
        val centerX = plot.left + slot * (index + 0.5f)
        val valueY = plot.top + ChartScale.toY(value, range, plot.height)
        val top = min(valueY, zeroY)
        val barHeight = max(kotlin.math.abs(valueY - zeroY), 1f)
        drawRect(
            color = if (index == highlightIndex) highlightColor else barColor,
            topLeft = Offset(centerX - barWidth / 2f, top),
            size = Size(barWidth, barHeight),
        )
        if (showValues && value != 0.0) {
            val layout = measurer.measure(yFormatter(value), labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = (centerX - layout.size.width / 2f)
                        .coerceIn(0f, size.width - layout.size.width),
                    y = (top - layout.size.height).coerceAtLeast(0f),
                ),
            )
        }
    }
    drawXAxis(plot, xLabels, measurer, labelStyle, gap)
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun BarChartCardPreview() {
    MyHealthTheme(dynamicColor = false) {
        BarChartCard(
            title = "Sleep",
            values = listOf(7.2, 6.4, 8.1, 5.9, 7.7, 6.8, 7.0),
            xLabels = listOf("Mon", "Thu", "Sun"),
            yFormatter = { fmtDecimal(it, 1) },
            highlightIndex = 6,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 380, name = "Many bars")
@Composable
private fun BarChartCardManyPreview() {
    MyHealthTheme(dynamicColor = false) {
        BarChartCard(
            title = "Daily TRIMP",
            values = (0 until 28).map { (it * 13 % 90).toDouble() },
            xLabels = listOf("Aug 1", "Aug 14", "Aug 28"),
            yFormatter = { fmtDecimal(it, 0) },
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 380, name = "Empty")
@Composable
private fun BarChartCardEmptyPreview() {
    MyHealthTheme(dynamicColor = false) {
        BarChartCard(
            title = "Daily TRIMP",
            values = emptyList(),
            xLabels = emptyList(),
            yFormatter = { fmtDecimal(it, 0) },
            modifier = Modifier.padding(16.dp),
        )
    }
}
