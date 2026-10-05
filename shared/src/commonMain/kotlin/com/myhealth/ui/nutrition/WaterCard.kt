package com.myhealth.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.WaterLog
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.zeroPad
import com.myhealth.ui.theme.MyHealthTheme

/** The quick-add amounts of the water card (PLAN P4.13). */
val WATER_QUICK_AMOUNTS_ML: List<Int> = listOf(250, 500)

/** Callbacks of [WaterCard]. */
data class WaterActions(
    val onAdd: (Int) -> Unit,
    val onOpenCustom: () -> Unit,
    val onDelete: (Long) -> Unit,
)

/**
 * Water logging for the shown day (PLAN P4.13): the day total against the target's `waterMl`, a
 * progress bar, the +250 / +500 ml quick buttons plus a custom amount, and the day's entries with
 * a delete affordance.
 */
@Composable
fun WaterCard(
    target: NutritionTarget?,
    totalMl: Int,
    logs: List<WaterLog>,
    actions: WaterActions,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = stringResource(Res.string.water_title), modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = waterLabel(target, totalMl),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = waterRemainingLabel(target, totalMl),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { waterFraction(target, totalMl) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WATER_QUICK_AMOUNTS_ML.forEach { amount ->
                OutlinedButton(onClick = { actions.onAdd(amount) }) {
                    Text(stringResource(Res.string.water_quick_add_button, amount))
                }
            }
            OutlinedButton(onClick = actions.onOpenCustom) { Text(stringResource(Res.string.water_custom_button)) }
        }
        logs.forEach { log ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.water_entry_row, waterEntryTime(log), log.ml),
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { actions.onDelete(log.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.water_delete_drink_cd))
                }
            }
        }
        if (logs.isEmpty()) {
            Text(
                text = stringResource(Res.string.water_empty_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The custom-amount dialog behind the card's "Custom" button. */
@Composable
fun WaterAmountDialog(
    amountMl: Double?,
    onAmountChange: (Double?) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.water_dialog_title)) },
        text = {
            NumberField(
                label = stringResource(Res.string.water_millilitres_label),
                value = amountMl,
                onValueChange = onAmountChange,
                decimals = 0,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.quantity_add_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** "1.3 l left" / "250 ml left" / "Target reached", or an empty string with no target. */
fun waterRemainingLabel(target: NutritionTarget?, totalMl: Int): String {
    val goal = target?.waterMl ?: return ""
    val remaining = goal - totalMl
    if (remaining <= 0) return "Target reached"
    return "${waterAmountLabel(remaining)} left"
}

/** "07:20" from a `water_log` row's minute of day, or "—" when it has none. */
fun waterEntryTime(log: WaterLog): String {
    val minute = log.atMinuteOfDay ?: return "—"
    return "${(minute / 60).zeroPad(2)}:${(minute % 60).zeroPad(2)}"
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun WaterCardPreview() {
    MyHealthTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WaterCard(
                target = previewTarget(),
                totalMl = 1500,
                logs = listOf(
                    WaterLog(id = 1L, day = 20_000L, atMinuteOfDay = 7 * 60 + 20, ml = 500),
                    WaterLog(id = 2L, day = 20_000L, atMinuteOfDay = 12 * 60, ml = 1000),
                ),
                actions = WaterActions(onAdd = {}, onOpenCustom = {}, onDelete = {}),
            )
            WaterCard(
                target = null,
                totalMl = 0,
                logs = emptyList(),
                actions = WaterActions(onAdd = {}, onOpenCustom = {}, onDelete = {}),
            )
        }
    }
}
