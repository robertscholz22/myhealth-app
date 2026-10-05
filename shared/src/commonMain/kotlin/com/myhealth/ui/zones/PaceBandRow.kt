package com.myhealth.ui.zones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import com.myhealth.domain.engine.running.PaceZoneBand

/** The confidence sentence for [band] (PLAN §3.10.2): which of the four the screen shows depends
 * only on [ConfidenceMessageKind]; the run count is the only value that is ever interpolated. */
@Composable
private fun confidenceText(band: PaceZoneBand): String = when (confidenceMessageKind(band.confidence)) {
    ConfidenceMessageKind.MEASURED -> stringResource(Res.string.zones_confidence_measured, band.activities)
    ConfidenceMessageKind.PARTLY_MODELLED -> stringResource(Res.string.zones_confidence_partly_modelled)
    ConfidenceMessageKind.MODELLED -> stringResource(Res.string.zones_confidence_modelled)
    ConfidenceMessageKind.NOT_ENOUGH_DATA -> stringResource(Res.string.zones_confidence_not_enough_data)
}

/**
 * One zone's pace band (PLAN §3.10.2, §4.2 "Zones & paces"): "Z2" on the left, the band ("4:09–4:22
 * /km", or a placeholder when there is none) and its confidence sentence on the right.
 */
@Composable
fun PaceBandRow(band: PaceZoneBand, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Z${band.zone}", style = MaterialTheme.typography.bodyLarge)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = paceBandLabel(band) ?: stringResource(Res.string.zones_pace_placeholder),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = confidenceText(band),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
