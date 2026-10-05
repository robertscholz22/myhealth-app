package com.myhealth.ui.common.body

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.ui.theme.MyHealthTheme

/** Which set of swatches [BodyFigureLegend] shows (§3.12.2 / §4.2 "Load & recovery", P14.8). */
enum class BodyFigureLegendKind { PRIMARY_SECONDARY, LOAD_BAND }

/**
 * The legend under a [BodyFigure] (PLAN §3.12.2): "Primary / Secondary" for an exercise or a
 * workout's figure, "Fresh / Loaded / Fatigued" for the Load screen's heat map (P14.8) — the same
 * three colours [BodyFigure] itself draws (`surfaceVariant` → `primary`), so the legend can never
 * disagree with the figure it explains.
 */
@Composable
fun BodyFigureLegend(kind: BodyFigureLegendKind, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        when (kind) {
            BodyFigureLegendKind.PRIMARY_SECONDARY -> {
                LegendItem(MaterialTheme.colorScheme.primary, stringResource(Res.string.body_figure_legend_primary))
                LegendItem(
                    MaterialTheme.colorScheme.primary.copy(alpha = SECONDARY_ALPHA),
                    stringResource(Res.string.body_figure_legend_secondary),
                )
            }
            BodyFigureLegendKind.LOAD_BAND -> {
                LegendItem(MaterialTheme.colorScheme.surfaceVariant, stringResource(Res.string.body_figure_legend_fresh))
                LegendItem(
                    MaterialTheme.colorScheme.primary.copy(alpha = LOADED_ALPHA),
                    stringResource(Res.string.body_figure_legend_loaded),
                )
                LegendItem(MaterialTheme.colorScheme.primary, stringResource(Res.string.body_figure_legend_fatigued))
            }
        }
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

private const val SECONDARY_ALPHA = 0.35f
private const val LOADED_ALPHA = 0.6f

@Preview(showBackground = true)
@Composable
private fun BodyFigureLegendPreview() {
    MyHealthTheme(dynamicColor = false) {
        BodyFigureLegend(kind = BodyFigureLegendKind.PRIMARY_SECONDARY)
    }
}
