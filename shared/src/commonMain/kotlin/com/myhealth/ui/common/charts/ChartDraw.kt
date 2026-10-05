package com.myhealth.ui.common.charts

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText

/** The rectangle the data itself is drawn into, after the axis gutters have been reserved. */
internal data class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun toRect(): Rect = Rect(left, top, right, bottom)
}

/**
 * Reserves a left gutter wide enough for the widest y label and a bottom strip for the x labels,
 * so nothing is ever clipped at the canvas edge (the failure mode P8.2's acceptance screenshots
 * look for).
 */
internal fun layoutPlot(
    canvasWidth: Float,
    canvasHeight: Float,
    yLabels: List<String>,
    hasXLabels: Boolean,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    gapPx: Float,
    topInsetPx: Float,
): Plot {
    val gutter = yLabels.maxOfOrNull { measurer.measure(it, labelStyle).size.width.toFloat() } ?: 0f
    val lineHeight = measurer.measure("0", labelStyle).size.height.toFloat()
    val bottom = if (hasXLabels) canvasHeight - lineHeight - gapPx else canvasHeight - gapPx
    return Plot(
        left = gutter + gapPx,
        top = topInsetPx + lineHeight / 2f,
        right = canvasWidth - gapPx,
        bottom = bottom,
    )
}

/** Horizontal grid lines at every tick, each with its right-aligned y label in the left gutter. */
internal fun DrawScope.drawYAxis(
    plot: Plot,
    range: NiceRange,
    yFormatter: (Double) -> String,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    gridColor: Color,
    gapPx: Float,
) {
    range.ticks.forEach { tick ->
        val y = plot.top + ChartScale.toY(tick, range, plot.height)
        drawLine(
            color = gridColor,
            start = Offset(plot.left, y),
            end = Offset(plot.right, y),
            strokeWidth = 1f,
        )
        val layout: TextLayoutResult = measurer.measure(yFormatter(tick), labelStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = (plot.left - gapPx - layout.size.width).coerceAtLeast(0f),
                y = y - layout.size.height / 2f,
            ),
        )
    }
}

/**
 * Up to three x labels — first, middle, last — anchored to the left edge, the centre and the right
 * edge of the plot so they cannot run off the canvas or overlap each other.
 */
internal fun DrawScope.drawXAxis(
    plot: Plot,
    xLabels: List<String>,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    gapPx: Float,
) {
    val picked = pickXLabels(xLabels)
    if (picked.isEmpty()) return
    val y = plot.bottom + gapPx / 2f
    picked.forEachIndexed { index, label ->
        val layout = measurer.measure(label, labelStyle)
        val x = when {
            picked.size == 1 -> plot.left + (plot.width - layout.size.width) / 2f
            index == 0 -> plot.left
            index == picked.lastIndex -> plot.right - layout.size.width
            else -> plot.left + (plot.width - layout.size.width) / 2f
        }
        drawText(textLayoutResult = layout, topLeft = Offset(x.coerceAtLeast(0f), y))
    }
}

/** First / middle / last of [xLabels], with blanks and duplicates collapsed. */
internal fun pickXLabels(xLabels: List<String>): List<String> {
    val clean = xLabels.filter { it.isNotBlank() }
    return when {
        clean.isEmpty() -> emptyList()
        clean.size <= 3 -> clean
        else -> listOf(clean.first(), clean[clean.size / 2], clean.last())
    }.distinct()
}
