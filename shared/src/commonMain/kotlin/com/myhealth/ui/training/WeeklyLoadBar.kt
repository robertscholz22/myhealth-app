package com.myhealth.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.ui.common.fmtInt
import com.myhealth.ui.theme.MyHealthTheme
import kotlin.math.roundToInt

/**
 * The three numbers the weekly load bar compares (PLAN §4.2 "Training plan": planned vs target vs
 * actual TRIMP), plus the bar geometry derived from them.
 *
 * Every bar is drawn relative to the **largest** of the three, so the tallest one always fills the
 * row and the other two are readable against it; a week with nothing in it draws three empty bars
 * rather than dividing by zero.
 */
data class WeeklyLoadSums(
    val planned: Double,
    val target: Double,
    val actual: Double,
) {
    /** The scale every bar is drawn against; never 0, so [fractionOf] is always defined. */
    val scale: Double get() = maxOf(planned, target, actual).coerceAtLeast(1.0)

    fun fractionOf(value: Double): Float = (value / scale).coerceIn(0.0, 1.0).toFloat()

    val plannedFraction: Float get() = fractionOf(planned)
    val targetFraction: Float get() = fractionOf(target)
    val actualFraction: Float get() = fractionOf(actual)

    /** True once the week's recorded load has reached the target the suggester set. */
    val onTarget: Boolean get() = target > 0.0 && actual >= target

    companion object {
        val ZERO: WeeklyLoadSums = WeeklyLoadSums(planned = 0.0, target = 0.0, actual = 0.0)
    }
}

/**
 * The pure helper behind the bar (unit-tested in `WeeklyLoadBarTest`).
 *
 * - **planned** sums `estimatedTrimp` over the sessions still outstanding (`PLANNED`): a session
 *   that was completed has already turned into real load and would otherwise be counted twice,
 *   and a `SKIPPED`/`MOVED` one will never happen at all.
 * - **actual** sums `daily_load.trimp`, i.e. what the load engine (§3.2) actually measured.
 * - **target** is the suggester's weekly AU budget (§3.5.2), passed through unchanged.
 */
fun weeklyLoadSums(
    planned: List<PlannedSession>,
    target: Double,
    actual: List<DailyLoad>,
): WeeklyLoadSums = WeeklyLoadSums(
    planned = planned
        .filter { it.status == PlannedStatus.PLANNED }
        .sumOf { it.estimatedTrimp ?: 0.0 },
    target = target.coerceAtLeast(0.0),
    actual = actual.sumOf { it.trimp },
)

/** Planned vs target vs actual TRIMP for one week (§4.2 "Training plan"). */
@Composable
fun WeeklyLoadBar(sums: WeeklyLoadSums, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LoadBarRow(
            label = stringResource(Res.string.training_load_planned),
            value = sums.planned,
            fraction = sums.plannedFraction,
            color = MaterialTheme.colorScheme.secondary,
        )
        LoadBarRow(
            label = stringResource(Res.string.training_load_target),
            value = sums.target,
            fraction = sums.targetFraction,
            color = MaterialTheme.colorScheme.outline,
        )
        LoadBarRow(
            label = stringResource(Res.string.training_load_actual),
            value = sums.actual,
            fraction = sums.actualFraction,
            color = if (sums.onTarget) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
        )
    }
}

@Composable
private fun LoadBarRow(label: String, value: Double, fraction: Float, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        LinearProgressIndicator(
            progress = { fraction },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).height(10.dp),
        )
        Text(text = "${fmtInt(value.roundToInt())} AU", style = MaterialTheme.typography.labelMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun WeeklyLoadBarPreview() {
    MyHealthTheme(dynamicColor = false) {
        WeeklyLoadBar(
            sums = WeeklyLoadSums(planned = 420.0, target = 620.0, actual = 310.0),
            modifier = Modifier.padding(16.dp),
        )
    }
}
