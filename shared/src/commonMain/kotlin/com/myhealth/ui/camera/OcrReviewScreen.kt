package com.myhealth.ui.camera

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.myhealth.resources.*
import com.myhealth.ui.common.loadHalfSizeImage
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFacts
import com.myhealth.domain.model.NutritionFactsDraft
import com.myhealth.domain.model.ParsedValue
import com.myhealth.domain.util.EngineWarning
import com.myhealth.ui.common.CARD_CORNER_RADIUS
import com.myhealth.ui.common.ConfidenceLevel
import com.myhealth.ui.common.ConfidenceUnderline
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.ErrorBanner
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.theme.MyHealthTheme

/**
 * Review of a scanned label before it becomes an ingredient (PLAN §4.2 "OCR review", P4.9): the
 * captured picture, then one editable field per parsed value, each with its confidence colour
 * (green ≥ 0.9, amber ≥ 0.7, red below — and the first red field takes focus), the parser's
 * warnings, and the per-serving column toggle when the label had two columns.
 *
 * "Accept" carries the confirmed numbers to the ingredient editor; it **never** saves by itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrReviewScreen(
    onBack: () -> Unit,
    onRetake: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVm { graph -> OcrReviewViewModel(graph.draftStore) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ocr_review_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.discard()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        OcrReviewBody(
            state = state,
            onValue = vm::setValue,
            onPerServing = vm::setUsePerServing,
            onName = vm::setName,
            onBrand = vm::setBrand,
            onRetake = {
                vm.discard()
                onRetake()
            },
            onCancel = {
                vm.discard()
                onBack()
            },
            onAccept = {
                vm.accept()
                onAccept()
            },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

@Composable
private fun OcrReviewBody(
    state: OcrReviewUiState,
    onValue: (OcrField, Double?) -> Unit,
    onPerServing: (Boolean) -> Unit,
    onName: (String) -> Unit,
    onBrand: (String) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.missing) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(Res.string.ocr_review_missing_title),
                message = stringResource(Res.string.ocr_review_missing_message),
                actionLabel = stringResource(Res.string.ocr_review_scan_again_action),
                onAction = onRetake,
            )
        }
        return
    }

    val focusRequester = remember { FocusRequester() }
    val focusField = remember(state.usePerServing, state.imagePath) { state.firstLowConfidence }
    LaunchedEffect(focusField) {
        if (focusField != null) runCatching { focusRequester.requestFocus() }
    }

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.imagePath?.let { path -> item { CapturedImage(path) } }
            if (state.warnings.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.warnings.distinctBy { it.message }.forEach { warning ->
                            ErrorBanner(message = warning.message)
                        }
                    }
                }
            }
            item {
                SectionCard(title = stringResource(Res.string.ocr_review_section_product)) {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = onName,
                        label = { Text(stringResource(Res.string.ocr_review_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.brand,
                        onValueChange = onBrand,
                        label = { Text(stringResource(Res.string.ocr_review_brand_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                SectionCard(title = stringResource(Res.string.ocr_review_section_recognised_values)) {
                    Text(
                        text = state.basisNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.hasPerServing) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(Res.string.ocr_review_use_per_serving), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = state.usePerServing, onCheckedChange = onPerServing)
                        }
                    }
                    OcrField.entries.forEach { field ->
                        val row = state.valueOf(field)
                        // `key` gives every column swap a fresh NumberField, so the text buffer
                        // it keeps internally shows the swapped value instead of the old one.
                        key(state.usePerServing, field) {
                            OcrValueRow(
                                row = row,
                                onValue = { value -> onValue(field, value) },
                                modifier = if (field == focusField) {
                                    Modifier.focusRequester(focusRequester)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.action_cancel)) }
            OutlinedButton(onClick = onRetake) { Text(stringResource(Res.string.ocr_review_retake_action)) }
            Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.ocr_review_accept_action)) }
        }
    }
}

@Composable
private fun OcrValueRow(
    row: OcrFieldValue,
    onValue: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val fieldLabel = stringResource(row.field.labelRes)
        NumberField(
            label = if (row.isUpperBound) {
                stringResource(Res.string.ocr_review_upper_bound_label, fieldLabel)
            } else {
                fieldLabel
            },
            value = row.value,
            onValueChange = onValue,
            suffix = stringResource(row.field.suffixRes),
            decimals = row.field.decimals,
            isError = row.value != null && row.level == ConfidenceLevel.LOW,
            modifier = modifier,
        )
        if (row.value != null) {
            ConfidenceUnderline(confidence = row.confidence)
        }
    }
}

/**
 * The captured JPEG, decoded off the main thread and downscaled — it is only ever shown a few
 * hundred pixels wide, so the full sensor bitmap would be wasted memory. The recognised boxes are
 * deliberately not drawn over it: the draft keeps line *indices*, not line geometry, so painting
 * them would mean carrying every `OcrLine` through the hand-off for a purely decorative overlay.
 */
@Composable
private fun CapturedImage(path: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) { value = loadHalfSizeImage(path) }
    val current = bitmap ?: return
    Image(
        bitmap = current,
        contentDescription = stringResource(Res.string.ocr_review_captured_image_content_description),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(CARD_CORNER_RADIUS)),
    )
}

@Preview(showBackground = true, widthDp = 380, heightDp = 900, name = "Low-confidence scan")
@Composable
private fun OcrReviewLowConfidencePreview() {
    val draft = NutritionFactsDraft(
        basis = MeasureBasis.PER_100G,
        energyKcal = ParsedValue(412.0, 0.95, 3),
        energyKj = ParsedValue(1724.0, 0.9, 3),
        fatG = ParsedValue(17.4, 0.55, 4),
        satFatG = ParsedValue(8.1, 0.45, 5),
        carbsG = ParsedValue(53.0, 0.88, 6),
        sugarG = ParsedValue(21.0, 0.72, 7),
        fiberG = ParsedValue(4.2, 0.6, 8),
        proteinG = ParsedValue(7.8, 0.93, 9),
        saltG = ParsedValue(0.55, 0.68, 10),
        servingGrams = 30.0,
        servingLabel = "1 bar (30 g)",
        perServing = NutritionFacts(
            basis = MeasureBasis.PER_PIECE,
            kcal = 124.0,
            proteinG = 2.3,
            carbsG = 15.9,
            sugarG = 6.3,
            fatG = 5.2,
            satFatG = 2.4,
            fiberG = 1.3,
            saltG = 0.17,
            sodiumG = 0.07,
        ),
    )
    MyHealthTheme(dynamicColor = false) {
        OcrReviewBody(
            state = reviewStateOf(
                ScanDraft(
                    facts = draft,
                    warnings = listOf(
                        EngineWarning(
                            EngineWarningCode.LOW_CONFIDENCE,
                            "Several values were read with low confidence — check fat and saturates.",
                        ),
                    ),
                    name = "Fruit & nut bar",
                    brand = "Storebrand",
                ),
            ),
            onValue = { _, _ -> },
            onPerServing = {},
            onName = {},
            onBrand = {},
            onRetake = {},
            onCancel = {},
            onAccept = {},
        )
    }
}
