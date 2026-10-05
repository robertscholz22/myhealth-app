package com.myhealth.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.ui.theme.MyHealthTheme

/** How much the OCR parser trusts a field (PLAN P4.9: ≥ 0.9 green, ≥ 0.7 amber, else red). */
enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

/** The §P4.9 thresholds, in one place: the review form colours and focuses by this. */
fun confidenceLevelOf(confidence: Double): ConfidenceLevel = when {
    confidence >= 0.9 -> ConfidenceLevel.HIGH
    confidence >= 0.7 -> ConfidenceLevel.MEDIUM
    else -> ConfidenceLevel.LOW
}

/** Short label shown next to a field, e.g. "low confidence (0.55) — check this". */
@Composable
fun confidenceLabel(confidence: Double): String = when (confidenceLevelOf(confidence)) {
    ConfidenceLevel.HIGH -> stringResource(Res.string.common_confidence_high)
    ConfidenceLevel.MEDIUM -> stringResource(Res.string.common_confidence_medium)
    ConfidenceLevel.LOW -> stringResource(Res.string.common_confidence_low)
}

/**
 * The confidence bar under an OCR-filled field (§4.3 `ConfidenceUnderline(confidence)`): a full
 * width track with the confidence-coloured part filled in, plus the wording above. Drawn with
 * `Canvas` — the app ships no chart/library dependency (amendment A2).
 */
@Composable
fun ConfidenceUnderline(
    confidence: Double,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val level = confidenceLevelOf(confidence)
    val color = level.color()
    Column(modifier = modifier.fillMaxWidth().padding(top = 2.dp)) {
        if (showLabel) {
            Text(
                text = confidenceLabel(confidence),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(3.dp)) {
            drawRect(color = color.copy(alpha = 0.25f), size = size)
            drawRect(
                color = color,
                size = size.copy(width = size.width * confidence.coerceIn(0.0, 1.0).toFloat()),
            )
        }
    }
}

@Composable
internal fun ConfidenceLevel.color(): Color = when (this) {
    // Deliberately fixed hues rather than theme roles: green/amber/red is the meaning here.
    ConfidenceLevel.HIGH -> Color(0xFF2E7D32)
    ConfidenceLevel.MEDIUM -> Color(0xFFF9A825)
    ConfidenceLevel.LOW -> MaterialTheme.colorScheme.error
}

@Preview(showBackground = true, widthDp = 320)
@Composable
private fun ConfidenceUnderlinePreview() {
    MyHealthTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            ConfidenceUnderline(confidence = 0.96)
            ConfidenceUnderline(confidence = 0.78)
            ConfidenceUnderline(confidence = 0.45)
        }
    }
}
