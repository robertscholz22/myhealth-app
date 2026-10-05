package com.myhealth.ui.today

import androidx.compose.foundation.clickable
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
import com.myhealth.domain.engine.strength.MuscleLoadState
import com.myhealth.domain.model.DailyLoad
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.StatTile
import com.myhealth.ui.common.fmtDecimal
import com.myhealth.ui.load.acwrZoneOf
import com.myhealth.ui.load.color
import com.myhealth.ui.load.flagExplanation
import com.myhealth.ui.load.labelRes
import com.myhealth.ui.load.recoveryBandLabel
import com.myhealth.ui.load.todayMuscleLoadHint

/**
 * Today's recovery card (§4.2 Today, P5.8): score, band chip, confidence and the top active flag,
 * or a "not enough data yet" state before the first `daily_load` row exists. `daily_load` only
 * caches the score's totals, not its component breakdown — that detail lives on the Load screen
 * (P5.6), which this card taps through to.
 */
@Composable
internal fun RecoveryCard(load: DailyLoad?, topFlag: String?, onOpenLoad: () -> Unit) {
    SectionCard(
        title = stringResource(Res.string.today_recovery_title),
        modifier = Modifier.clickable(onClick = onOpenLoad),
    ) {
        if (load?.recoveryScore == null) {
            Text(stringResource(Res.string.today_recovery_no_data), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${load.recoveryScore} / 100", style = MaterialTheme.typography.headlineMedium)
            Column(horizontalAlignment = Alignment.End) {
                Text(recoveryBandLabel(load.recoveryBand), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        Res.string.today_confidence_label,
                        fmtDecimal(load.recoveryConfidence * 100, 0),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (topFlag != null) {
            Text(flagExplanation(topFlag), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Today's load card (§4.2 Today, P5.8): ACWR with its zone colour, ATL/CTL, and the last 7 days'
 * summed TRIMP. Taps through to the Load & Recovery screen for the full series. */
@Composable
internal fun LoadCard(
    load: DailyLoad?,
    weeklyTrimp: Double,
    muscleLoad: MuscleLoadState?,
    onOpenLoad: () -> Unit,
) {
    SectionCard(
        title = stringResource(Res.string.today_load_title),
        modifier = Modifier.clickable(onClick = onOpenLoad),
    ) {
        if (load == null) {
            Text(stringResource(Res.string.today_load_no_data), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val zone = acwrZoneOf(load.acwr)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    stringResource(Res.string.today_acwr_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = load.acwr?.let { fmtDecimal(it, 2) } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    color = zone?.color() ?: MaterialTheme.colorScheme.onSurface,
                )
            }
            StatTile(label = stringResource(Res.string.today_atl_label), value = fmtDecimal(load.atl, 0))
            StatTile(label = stringResource(Res.string.today_ctl_label), value = fmtDecimal(load.ctl, 0))
            StatTile(
                label = stringResource(Res.string.today_weekly_trimp_label),
                value = fmtDecimal(weeklyTrimp, 0),
            )
        }
        muscleLoad?.let(::todayMuscleLoadHint)?.let { hint ->
            Text(stringResource(hint.labelRes()), style = MaterialTheme.typography.bodySmall)
        }
    }
}
