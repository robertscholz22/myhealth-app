package com.myhealth.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.theme.MyHealthTheme

/**
 * Quantity entry for one picked ingredient (PLAN §4.2 Add food, P4.6): a quantity field, the unit
 * dropdown restricted to [validUnitsFor], the quick-add chips of [quickChipsFor] and a live macro
 * preview computed with `MealMath`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuantityEditor(
    ingredient: Ingredient,
    quantity: Double?,
    unit: QuantityUnit,
    units: List<QuantityUnit>,
    chips: List<QuickChip>,
    preview: MacroTotals?,
    canAdd: Boolean,
    onQuantityChange: (Double?) -> Unit,
    onUnitChange: (QuantityUnit) -> Unit,
    onChipClick: (QuickChip) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = ingredient.name, style = MaterialTheme.typography.titleMedium)
            ingredient.brand?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { chip ->
                        AssistChip(onClick = { onChipClick(chip) }, label = { Text(chip.label) })
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumberField(
                    label = stringResource(Res.string.quantity_label),
                    value = quantity,
                    onValueChange = onQuantityChange,
                    decimals = 0,
                    modifier = Modifier.weight(1f),
                )
                DropdownField(
                    label = stringResource(Res.string.quantity_unit_label),
                    options = units,
                    selected = unit,
                    optionLabel = { it.label() },
                    onSelect = onUnitChange,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = preview?.let { previewLabel(it) } ?: stringResource(Res.string.quantity_enter_prompt),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.action_cancel)) }
                Button(onClick = onAdd, enabled = canAdd, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.quantity_add_button))
                }
            }
        }
    }
}

@Composable
internal fun previewLabel(totals: MacroTotals): String = stringResource(
    Res.string.quantity_preview_summary,
    roundHalfUp(totals.kcal),
    totals.proteinG,
    totals.carbsG,
    totals.fatG,
)

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun QuantityEditorPreview() {
    val ingredient = Ingredient(
        id = 1L,
        name = "Rolled oats",
        brand = "Bob's Red Mill",
        barcode = null,
        basis = MeasureBasis.PER_100G,
        pieceGrams = null,
        servingGrams = 40.0,
        servingLabel = "1 scoop (40 g)",
        kcal = 370.0,
        proteinG = 13.0,
        carbsG = 60.0,
        sugarG = 1.0,
        fatG = 7.0,
        satFatG = 1.2,
        fiberG = 10.0,
        saltG = 0.02,
        sodiumG = 0.008,
        isFavorite = false,
        source = "MANUAL",
        offProductJson = null,
        lastUsedAtMillis = null,
        useCount = 0,
        archived = false,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )
    MyHealthTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = previewLabel(MacroTotals(296.0, 10.4, 48.0, 5.6, 8.0, 0.8, 1.0, 0.02)))
            Text(text = quickChipsFor(ingredient).joinToString { it.label })
        }
    }
}
