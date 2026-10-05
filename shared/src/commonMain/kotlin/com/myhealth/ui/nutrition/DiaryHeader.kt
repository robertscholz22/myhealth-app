package com.myhealth.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.util.EngineWarning
import com.myhealth.ui.calendar.MacroProgressRow
import com.myhealth.ui.calendar.targetProgressRows
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.theme.MyHealthTheme

/**
 * The diary's target-vs-intake header (PLAN §4.2 Nutrition diary, P4.5/P4.12): an energy bar with
 * the remaining kcal, one bar per macro, and the "Why this target?" expander over the snapshot's
 * `explanation` plus any engine warnings.
 *
 * With no `nutrition_target_snapshot` for the day it degrades to the day's intake plus a line
 * saying where targets come from; the diary asks for one on open (`ensureTarget`), so that state is
 * only reached before onboarding is finished.
 */
@Composable
fun DiaryHeader(
    target: NutritionTarget?,
    intake: MacroTotals,
    explanationExpanded: Boolean,
    onToggleExplanation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = if (target == null) {
        stringResource(Res.string.diary_title_intake)
    } else {
        stringResource(Res.string.diary_title_target_vs_intake)
    }
    SectionCard(title = title, modifier = modifier) {
        if (target == null) {
            Text(
                text = stringResource(
                    Res.string.diary_intake_summary,
                    intake.kcal,
                    intake.proteinG,
                    intake.carbsG,
                    intake.fatG,
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = NO_TARGET_MESSAGE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(Res.string.diary_kcal_over_target, roundHalfUp(intake.kcal), target.kcal),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = remainingLabel(target, intake).orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { energyFraction(target, intake) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = dayTypeLabel(target.dayType),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        targetProgressRows(target, intake).drop(1).forEach { row -> MacroBarRow(row) }
        TextButton(onClick = onToggleExplanation) {
            Text(
                if (explanationExpanded) {
                    stringResource(Res.string.diary_hide_explanation)
                } else {
                    stringResource(Res.string.diary_why_target)
                },
            )
        }
        if (explanationExpanded) {
            Text(
                text = target.explanation.ifBlank { stringResource(Res.string.diary_no_explanation) },
                style = MaterialTheme.typography.bodySmall,
            )
            warningLines(target).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun MacroBarRow(row: MacroProgressRow) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = row.label, style = MaterialTheme.typography.bodyMedium)
            Text(text = row.valueLabel, style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = { row.fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

private fun dayTypeLabel(dayType: DayType): String =
    dayType.name.split("_").joinToString(" ") { it.lowercase() }
        .replaceFirstChar(Char::uppercase)

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun DiaryHeaderPreview() {
    MyHealthTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DiaryHeader(
                target = previewTarget(),
                intake = previewIntake(),
                explanationExpanded = true,
                onToggleExplanation = {},
            )
            DiaryHeader(
                target = null,
                intake = previewIntake(),
                explanationExpanded = false,
                onToggleExplanation = {},
            )
        }
    }
}

internal fun previewIntake(): MacroTotals = MacroTotals(
    kcal = 1820.0,
    proteinG = 121.0,
    carbsG = 198.0,
    fatG = 58.0,
    fiberG = 24.0,
    sugarG = 42.0,
    satFatG = 14.0,
    saltG = 4.2,
)

internal fun previewTarget(): NutritionTarget = NutritionTarget(
    day = 20_000L,
    kcal = 2230,
    proteinG = 150,
    carbsG = 250,
    fatG = 70,
    fiberG = 30,
    sugarCapG = 60,
    satFatCapG = 24,
    saltG = 6.0,
    waterMl = 2800,
    bmrKcal = 1720,
    tdeeKcal = 2480,
    dayType = DayType.TRAINING,
    explanation = "Training day: TDEE 2480 kcal minus a 250 kcal deficit, protein at 1.8 g/kg.",
    warnings = listOf(
        EngineWarning(EngineWarningCode.MISSING_WEIGHT, "No weight measured in the last 30 days."),
    ),
    inputsHash = "preview",
    computedAtMillis = 0L,
)
