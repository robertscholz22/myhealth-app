package com.myhealth.ui.nutrition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealLog
import com.myhealth.domain.model.MealLogItem
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.ui.calendar.displayName
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.theme.MyHealthTheme

/**
 * One slot of the diary (PLAN §4.2 Nutrition diary, P4.5): its logged items, the per-slot totals
 * and the "+ Add" entry point into `AddFoodRoute`. Tapping an item row opens the quantity dialog;
 * the row's overflow deletes the item, and a named meal's overflow deletes the whole log.
 */
@Composable
fun MealSlotSection(
    section: SlotSection,
    onAdd: () -> Unit,
    onEditItem: (MealLogItem) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onDeleteLog: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = section.slot.displayName(),
        modifier = modifier,
        action = { TextButton(onClick = onAdd) { Text(stringResource(Res.string.mealslot_add_button)) } },
    ) {
        if (section.isEmpty) {
            Text(
                text = stringResource(Res.string.mealslot_empty_slot),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        section.logs.forEach { log ->
            MealLogBlock(
                log = log,
                onEditItem = onEditItem,
                onDeleteItem = onDeleteItem,
                onDeleteLog = onDeleteLog,
            )
        }
        Text(
            text = slotTotalsLabel(section.totals),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun MealLogBlock(
    log: MealLog,
    onEditItem: (MealLogItem) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onDeleteLog: (Long) -> Unit,
) {
    val name = log.name?.takeIf { it.isNotBlank() }
    if (name != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            ItemOverflow(
                actions = listOf(stringResource(Res.string.mealslot_remove_meal) to { onDeleteLog(log.id) }),
                contentDescription = stringResource(Res.string.mealslot_meal_actions_cd),
            )
        }
    }
    log.items.forEach { item ->
        ItemRow(
            item = item,
            onClick = { onEditItem(item) },
            onDelete = { onDeleteItem(item.id) },
        )
    }
}

@Composable
private fun ItemRow(item: MealLogItem, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.nameSnapshot,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = quantityLabel(item.quantity, item.unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(Res.string.mealslot_item_kcal_protein, roundHalfUp(item.kcal), item.proteinG),
            style = MaterialTheme.typography.bodySmall,
        )
        ItemOverflow(
            actions = listOf(stringResource(Res.string.action_delete) to onDelete),
            contentDescription = stringResource(Res.string.mealslot_item_actions_cd),
        )
    }
}

/** Overflow menu for a row (the alternative to swipe-to-delete, §4.2). */
@Composable
private fun ItemOverflow(actions: List<Pair<String, () -> Unit>>, contentDescription: String) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = contentDescription)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
internal fun slotTotalsLabel(totals: MacroTotals): String = stringResource(
    Res.string.mealslot_totals_summary,
    roundHalfUp(totals.kcal),
    totals.proteinG,
    totals.carbsG,
    totals.fatG,
)

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun MealSlotSectionPreview() {
    MyHealthTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MealSlotSection(
                section = previewSection(),
                onAdd = {},
                onEditItem = {},
                onDeleteItem = {},
                onDeleteLog = {},
            )
            MealSlotSection(
                section = SlotSection(MealSlot.EVENING_SNACK, emptyList(), MacroTotals.ZERO),
                onAdd = {},
                onEditItem = {},
                onDeleteItem = {},
                onDeleteLog = {},
            )
        }
    }
}

internal fun previewSection(): SlotSection {
    val items = listOf(
        previewItem(1L, "Rolled oats", 80.0, QuantityUnit.G, 296.0, 10.5),
        previewItem(2L, "Whole milk", 200.0, QuantityUnit.ML, 128.0, 7.0),
    )
    return SlotSection(MealSlot.BREAKFAST, listOf(previewLog(items)), totalsOf(items))
}

private fun previewLog(items: List<MealLogItem>) = MealLog(
    id = 1L,
    day = 20_000L,
    atMinuteOfDay = 7 * 60 + 30,
    slot = MealSlot.BREAKFAST,
    name = "Oatmeal with berries",
    templateId = 1L,
    note = null,
    items = items,
    createdAtMillis = 0L,
    updatedAtMillis = 0L,
)

private fun previewItem(
    id: Long,
    name: String,
    quantity: Double,
    unit: QuantityUnit,
    kcal: Double,
    protein: Double,
) = MealLogItem(
    id = id,
    mealLogId = 1L,
    ingredientId = id,
    nameSnapshot = name,
    quantity = quantity,
    unit = unit,
    kcal = kcal,
    proteinG = protein,
    carbsG = 40.0,
    sugarG = 6.0,
    fatG = 6.0,
    satFatG = 2.0,
    fiberG = 8.0,
    saltG = 0.1,
)
