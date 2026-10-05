package com.myhealth.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.myhealth.domain.model.CycleConfidence
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.CycleStatus
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.cycle.countdownLabel
import com.myhealth.ui.cycle.dayOfCycleLabel
import com.myhealth.ui.cycle.phaseLabelRes
import com.myhealth.ui.theme.MyHealthTheme

/**
 * Compact "Cycle" card (PLAN §5 P11.3): phase, day of cycle, next-period countdown, tap through
 * to [com.myhealth.ui.cycle.CycleScreen]. Shown by [TodayScreen] only while tracking is enabled;
 * when there is no status yet (nothing logged), it invites the first log instead.
 */
@Composable
fun TodayCycleCard(status: CycleStatus?, onOpenCycle: () -> Unit) {
    SectionCard(
        title = stringResource(Res.string.today_cycle_title),
        modifier = Modifier.clickable(onClick = onOpenCycle),
    ) {
        if (status == null) {
            Text(stringResource(Res.string.today_cycle_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(phaseLabelRes(status.phase)), style = MaterialTheme.typography.titleMedium)
            Text(dayOfCycleLabel(status.dayOfCycle, status.cycleLengthDays), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = countdownLabel(status.day, status.nextPeriodStart),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TodayCycleCardPreview() {
    MyHealthTheme(dynamicColor = false) {
        TodayCycleCard(
            status = CycleStatus(
                day = 20_710L,
                dayOfCycle = 12,
                phase = CyclePhase.FOLLICULAR,
                isLateLuteal = false,
                isPredicted = false,
                cycleLengthDays = 28,
                periodLengthDays = 5,
                nextPeriodStart = 20_710L + 16,
                ovulationDay = 20_710L + 2,
                fertileWindow = (20_710L - 3)..(20_710L + 3),
                confidence = CycleConfidence.MEDIUM,
            ),
            onOpenCycle = {},
        )
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun TodayCycleCardEmptyPreview() {
    MyHealthTheme(dynamicColor = false) {
        TodayCycleCard(status = null, onOpenCycle = {})
    }
}
