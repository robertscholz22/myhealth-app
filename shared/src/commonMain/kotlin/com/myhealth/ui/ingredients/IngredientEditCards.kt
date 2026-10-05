package com.myhealth.ui.ingredients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.util.EngineWarning
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.ErrorBanner
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.resolve

/**
 * The four cards of [IngredientEditScreen] plus its warnings banner (PLAN P4.3), split out of the
 * screen file when the barcode lookup of P4.10 pushed it past the ~400-line limit (R10).
 */

@Composable
internal fun WarningsBanner(warnings: List<EngineWarning>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        warnings.distinctBy { it.message }.forEach { warning -> ErrorBanner(message = warning.message) }
    }
}

@Composable
internal fun IdentityCard(
    draft: IngredientDraft,
    errors: Map<IngredientField, UiMessage>,
    isLookingUp: Boolean,
    onDraftChange: ((IngredientDraft) -> IngredientDraft) -> Unit,
    onScan: () -> Unit,
    onLookUp: () -> Unit,
) {
    SectionCard(title = stringResource(Res.string.ingredient_edit_section_ingredient)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { name -> onDraftChange { it.copy(name = name) } },
            label = { Text(stringResource(Res.string.ingredient_edit_name_label)) },
            singleLine = true,
            isError = errors.containsKey(IngredientField.NAME),
            supportingText = errors[IngredientField.NAME]?.let { { Text(it.resolve()) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.brand,
            onValueChange = { brand -> onDraftChange { it.copy(brand = brand) } },
            label = { Text(stringResource(Res.string.ingredient_edit_brand_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft.barcode,
                onValueChange = { code -> onDraftChange { it.copy(barcode = code) } },
                label = { Text(stringResource(Res.string.ingredient_edit_barcode_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onScan) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null)
                Text(stringResource(Res.string.ingredient_edit_scan_action))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLookingUp) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            TextButton(onClick = onLookUp, enabled = !isLookingUp && draft.barcode.isNotBlank()) {
                Text(stringResource(Res.string.ingredient_edit_lookup_action))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.ingredient_edit_favorite_label), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = draft.isFavorite, onCheckedChange = { v -> onDraftChange { it.copy(isFavorite = v) } })
        }
    }
}

@Composable
internal fun BasisCard(
    draft: IngredientDraft,
    errors: Map<IngredientField, UiMessage>,
    onDraftChange: ((IngredientDraft) -> IngredientDraft) -> Unit,
) {
    val suffixes = MeasureBasis.entries.associateWith { it.unitSuffix() }
    SectionCard(title = stringResource(Res.string.ingredient_edit_section_basis)) {
        DropdownField(
            label = stringResource(Res.string.ingredient_edit_values_per_label),
            options = MeasureBasis.entries,
            selected = draft.basis,
            optionLabel = { suffixes.getValue(it) },
            onSelect = { basis -> onDraftChange { it.copy(basis = basis) } },
        )
        if (draft.basis == MeasureBasis.PER_PIECE) {
            NumberField(
                label = stringResource(Res.string.ingredient_edit_piece_weight_label),
                value = draft.pieceGrams,
                onValueChange = { v -> onDraftChange { it.copy(pieceGrams = v) } },
                suffix = stringResource(Res.string.ingredient_edit_grams_suffix),
                decimals = 1,
                isError = errors.containsKey(IngredientField.PIECE_GRAMS),
                supportingText = errors[IngredientField.PIECE_GRAMS]?.resolve(),
            )
        }
        NumberField(
            label = stringResource(Res.string.ingredient_edit_default_serving_label),
            value = draft.servingGrams,
            onValueChange = { v -> onDraftChange { it.copy(servingGrams = v) } },
            suffix = stringResource(Res.string.ingredient_edit_grams_suffix),
            decimals = 1,
        )
        OutlinedTextField(
            value = draft.servingLabel,
            onValueChange = { label -> onDraftChange { it.copy(servingLabel = label) } },
            label = { Text(stringResource(Res.string.ingredient_edit_serving_label_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun EnergyMacrosCard(
    draft: IngredientDraft,
    errors: Map<IngredientField, UiMessage>,
    onDraftChange: ((IngredientDraft) -> IngredientDraft) -> Unit,
) {
    val suffix = draft.basis.unitSuffix()
    SectionCard(title = stringResource(Res.string.ingredient_edit_section_energy_macros, suffix)) {
        NumberField(
            label = stringResource(Res.string.ingredient_edit_calories_label),
            value = draft.kcal,
            onValueChange = { v -> onDraftChange { it.copy(kcal = v) } },
            suffix = stringResource(Res.string.ingredient_edit_kcal_suffix),
            decimals = 0,
            isError = errors.containsKey(IngredientField.KCAL),
            supportingText = errors[IngredientField.KCAL]?.resolve(),
        )
        NumberField(
            label = stringResource(Res.string.ingredient_edit_protein_label),
            value = draft.proteinG,
            onValueChange = { v -> onDraftChange { it.copy(proteinG = v) } },
            suffix = stringResource(Res.string.ingredient_edit_grams_suffix),
            isError = errors.containsKey(IngredientField.PROTEIN),
            supportingText = errors[IngredientField.PROTEIN]?.resolve(),
        )
        NumberField(
            label = stringResource(Res.string.ingredient_edit_carbs_label),
            value = draft.carbsG,
            onValueChange = { v -> onDraftChange { it.copy(carbsG = v) } },
            suffix = stringResource(Res.string.ingredient_edit_grams_suffix),
            isError = errors.containsKey(IngredientField.CARBS),
            supportingText = errors[IngredientField.CARBS]?.resolve(),
        )
        NumberField(
            label = stringResource(Res.string.ingredient_edit_fat_label),
            value = draft.fatG,
            onValueChange = { v -> onDraftChange { it.copy(fatG = v) } },
            suffix = stringResource(Res.string.ingredient_edit_grams_suffix),
            isError = errors.containsKey(IngredientField.FAT),
            supportingText = errors[IngredientField.FAT]?.resolve(),
        )
    }
}

@Composable
internal fun MoreNutrientsCard(
    draft: IngredientDraft,
    errors: Map<IngredientField, UiMessage>,
    onDraftChange: ((IngredientDraft) -> IngredientDraft) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.ingredient_edit_section_more_nutrients)) {
        NumberField(
            label = stringResource(Res.string.ingredient_edit_sugars_label),
            value = draft.sugarG,
            onValueChange = { v -> onDraftChange { it.copy(sugarG = v) } },
            suffix = stringResource(Res.string.ingredient_edit_grams_suffix),
            isError = errors.containsKey(IngredientField.SUGAR),
            supportingText = errors[IngredientField.SUGAR]?.resolve(),
        )
        NumberField(
            label = stringResource(Res.string.ingredient_edit_saturates_label),
            value = draft.satFatG,
            onValueChange = { v -> onDraftChange { it.copy(satFatG = v) } },
            suffix = stringResource(Res.string.ingredient_edit_grams_suffix),
            isError = errors.containsKey(IngredientField.SAT_FAT),
            supportingText = errors[IngredientField.SAT_FAT]?.resolve(),
        )
        NumberField(
            label = stringResource(Res.string.ingredient_edit_fiber_label),
            value = draft.fiberG,
            onValueChange = { v -> onDraftChange { it.copy(fiberG = v) } },
            suffix = stringResource(Res.string.ingredient_edit_grams_suffix),
            isError = errors.containsKey(IngredientField.FIBER),
            supportingText = errors[IngredientField.FIBER]?.resolve(),
        )
        NumberField(
            label = stringResource(Res.string.ingredient_edit_salt_label),
            value = draft.saltG,
            onValueChange = { v -> onDraftChange { it.copy(saltG = v) } },
            suffix = stringResource(Res.string.ingredient_edit_grams_suffix),
            decimals = 2,
            isError = errors.containsKey(IngredientField.SALT),
            supportingText = errors[IngredientField.SALT]?.resolve(),
        )
        NumberField(
            label = stringResource(Res.string.ingredient_edit_sodium_label),
            value = draft.sodiumG,
            onValueChange = {},
            suffix = stringResource(Res.string.ingredient_edit_grams_suffix),
            decimals = 3,
            enabled = false,
        )
    }
}
