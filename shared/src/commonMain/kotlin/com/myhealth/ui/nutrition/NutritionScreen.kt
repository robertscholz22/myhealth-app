package com.myhealth.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.data.time.today
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVmWithSavedState
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.ui.calendar.fullDateTitle
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

/**
 * The nutrition diary (PLAN §4.2 Nutrition diary, P4.5). `NutritionRoute` opens it on today
 * ([epochDay] `null`); `NutritionDayRoute` on a specific day. Both use this screen — the shown day
 * is ViewModel state, so the ±1-day arrows work identically either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionScreen(
    epochDay: Long?,
    onAddFood: (Long, MealSlot) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVmWithSavedState { graph, handle ->
        NutritionViewModel(
            initialDay = epochDay ?: graph.clock.today().toEpochDays(),
            mealRepo = graph.mealRepo,
            nutritionRepo = graph.nutritionRepo,
            ingredientRepo = graph.ingredientRepo,
            clock = graph.clock,
            savedState = handle,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it.resolveText())
            vm.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.nav_nutrition)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::copyYesterday) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(Res.string.nutrition_copy_yesterday_cd))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        NutritionContent(
            state = state,
            actions = DiaryActions(
                onPreviousDay = vm::showPreviousDay,
                onNextDay = vm::showNextDay,
                onToggleExplanation = vm::toggleExplanation,
                onAddFood = { slot -> onAddFood(state.day, slot) },
                onEditItem = vm::editItem,
                onDeleteItem = vm::deleteItem,
                onDeleteLog = vm::deleteLog,
                onCopyYesterday = vm::copyYesterday,
                water = WaterActions(
                    onAdd = vm::addWater,
                    onOpenCustom = vm::openWaterDialog,
                    onDelete = vm::deleteWater,
                ),
            ),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }

    if (state.waterDialogOpen) {
        WaterAmountDialog(
            amountMl = state.waterDraftMl,
            onAmountChange = vm::setWaterDraft,
            onConfirm = vm::confirmWaterDialog,
            onDismiss = vm::cancelWaterDialog,
        )
    }

    state.editing?.let { edit ->
        QuantityDialog(
            edit = edit,
            onQuantityChange = vm::setEditQuantity,
            onUnitChange = vm::setEditUnit,
            onConfirm = vm::confirmEdit,
            onDismiss = vm::cancelEdit,
        )
    }
}

/** Callbacks of [NutritionContent]. */
data class DiaryActions(
    val onPreviousDay: () -> Unit,
    val onNextDay: () -> Unit,
    val onToggleExplanation: () -> Unit,
    val onAddFood: (MealSlot) -> Unit,
    val onEditItem: (com.myhealth.domain.model.MealLogItem) -> Unit,
    val onDeleteItem: (Long) -> Unit,
    val onDeleteLog: (Long) -> Unit,
    val onCopyYesterday: () -> Unit,
    val water: WaterActions,
)

@Composable
private fun NutritionContent(
    state: NutritionUiState,
    actions: DiaryActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DayStrip(
                day = state.day,
                onPrevious = actions.onPreviousDay,
                onNext = actions.onNextDay,
            )
        }
        item {
            DiaryHeader(
                target = state.target,
                intake = state.intake,
                explanationExpanded = state.explanationExpanded,
                onToggleExplanation = actions.onToggleExplanation,
            )
        }
        item {
            WaterCard(
                target = state.target,
                totalMl = state.waterMl,
                logs = state.waterLogs,
                actions = actions.water,
            )
        }
        if (!state.hasAnyMeal) {
            item {
                TextButton(onClick = actions.onCopyYesterday) {
                    Text(stringResource(Res.string.nutrition_copy_yesterday_button))
                }
            }
        }
        items(state.sections.size, key = { index -> state.sections[index].slot.name }) { index ->
            val section = state.sections[index]
            MealSlotSection(
                section = section,
                onAdd = { actions.onAddFood(section.slot) },
                onEditItem = actions.onEditItem,
                onDeleteItem = actions.onDeleteItem,
                onDeleteLog = actions.onDeleteLog,
            )
        }
    }
}

/** ±1-day arrows plus the shown date (§4.2: "day picker strip"). */
@Composable
private fun DayStrip(day: Long, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(Res.string.nutrition_previous_day_cd))
        }
        Text(
            text = fullDateTitle(LocalDate.fromEpochDays(day)),
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(Res.string.nutrition_next_day_cd))
        }
    }
}

@Composable
private fun QuantityDialog(
    edit: QuantityEdit,
    onQuantityChange: (Double?) -> Unit,
    onUnitChange: (QuantityUnit) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(edit.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    label = stringResource(Res.string.quantity_label),
                    value = edit.quantity,
                    onValueChange = onQuantityChange,
                    decimals = 0,
                )
                DropdownField(
                    label = stringResource(Res.string.quantity_unit_label),
                    options = edit.units,
                    selected = edit.unit,
                    optionLabel = { it.label() },
                    onSelect = onUnitChange,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Preview(showBackground = true, widthDp = 380, heightDp = 900)
@Composable
private fun NutritionContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        NutritionContent(
            state = NutritionUiState(
                isLoading = false,
                day = 20_000L,
                target = previewTarget(),
                sections = listOf(previewSection()) +
                    MealSlot.entries.drop(1).map { slot ->
                        SlotSection(slot, emptyList(), com.myhealth.domain.model.MacroTotals.ZERO)
                    },
                intake = previewIntake(),
                waterMl = 1_500,
            ),
            actions = DiaryActions(
                onPreviousDay = {},
                onNextDay = {},
                onToggleExplanation = {},
                onAddFood = {},
                onEditItem = {},
                onDeleteItem = {},
                onDeleteLog = {},
                onCopyYesterday = {},
                water = WaterActions(onAdd = {}, onOpenCustom = {}, onDelete = {}),
            ),
        )
    }
}
