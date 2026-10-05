package com.myhealth.ui.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.ui.common.DatePickerField
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.calendar.displayName
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

/** Meal templates list with per-template totals and "Log now" (PLAN §4.2, P4.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealTemplatesScreen(
    onBack: () -> Unit,
    onEditTemplate: (Long) -> Unit,
    onNewTemplate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVm { graph ->
        MealTemplatesViewModel(graph.mealRepo, graph.ingredientRepo, graph.clock)
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
                title = { Text(stringResource(Res.string.mealtpl_list_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewTemplate) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.mealtpl_new_cd))
            }
        },
    ) { innerPadding ->
        MealTemplatesContent(
            state = state,
            actions = MealTemplateListActions(
                onOpen = onEditTemplate,
                onToggleFavorite = vm::toggleFavorite,
                onLogNow = vm::openLogNow,
                onSetLogDay = vm::setLogDay,
                onSetLogSlot = vm::setLogSlot,
                onCancelLogNow = vm::cancelLogNow,
                onConfirmLogNow = vm::confirmLogNow,
            ),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

/** Callbacks of [MealTemplatesContent], grouped so the composable keeps a short signature. */
data class MealTemplateListActions(
    val onOpen: (Long) -> Unit,
    val onToggleFavorite: (MealTemplate) -> Unit,
    val onLogNow: (MealTemplate) -> Unit,
    val onSetLogDay: (Long) -> Unit,
    val onSetLogSlot: (MealSlot) -> Unit,
    val onCancelLogNow: () -> Unit,
    val onConfirmLogNow: () -> Unit,
)

@Composable
private fun MealTemplatesContent(
    state: MealTemplatesUiState,
    actions: MealTemplateListActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (state.rows.isEmpty()) {
            EmptyState(
                title = stringResource(Res.string.addfood_empty_templates_title),
                message = stringResource(Res.string.mealtpl_empty_message),
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(state.rows, key = { it.template.id }) { row ->
                    TemplateListRow(
                        row = row,
                        onOpen = { actions.onOpen(row.template.id) },
                        onToggleFavorite = { actions.onToggleFavorite(row.template) },
                        onLogNow = { actions.onLogNow(row.template) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    val template = state.logTemplate
    if (template != null) {
        LogNowDialog(
            template = template,
            day = state.logDay,
            slot = state.logSlot,
            onSetDay = actions.onSetLogDay,
            onSetSlot = actions.onSetLogSlot,
            onDismiss = actions.onCancelLogNow,
            onConfirm = actions.onConfirmLogNow,
        )
    }
}

@Composable
private fun TemplateListRow(
    row: TemplateRow,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLogNow: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.template.name) },
        supportingContent = { Text(macroSummary(row.totals, row.missingIngredients)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onLogNow) { Text(stringResource(Res.string.mealtpl_log_now_button)) }
                IconButton(onClick = onToggleFavorite) {
                    if (row.template.isFavorite) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(Res.string.mealtpl_unfavorite_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(Icons.Outlined.StarBorder, contentDescription = stringResource(Res.string.mealtpl_favorite_cd))
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    )
}

@Composable
private fun LogNowDialog(
    template: MealTemplate,
    day: Long,
    slot: MealSlot,
    onSetDay: (Long) -> Unit,
    onSetSlot: (MealSlot) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.mealtpl_log_dialog_title, template.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DatePickerField(
                    label = stringResource(Res.string.mealtpl_date_label),
                    value = LocalDate.fromEpochDays(day),
                    onValueChange = { onSetDay(it.toEpochDays()) },
                )
                DropdownField(
                    label = stringResource(Res.string.mealtpl_slot_label),
                    options = MealSlot.entries.toList(),
                    selected = slot,
                    optionLabel = { it.displayName() },
                    onSelect = onSetSlot,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.mealtpl_log_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** "520 kcal · 31 g P · 54 g C · 18 g F", with a note when an ingredient is missing. */
@Composable
internal fun macroSummary(totals: MacroTotals, missingIngredients: Boolean = false): String {
    val base = stringResource(
        Res.string.mealtpl_macro_summary,
        totals.kcal,
        totals.proteinG,
        totals.carbsG,
        totals.fatG,
    )
    return if (missingIngredients) stringResource(Res.string.mealtpl_macro_summary_missing, base) else base
}

@Preview(showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun MealTemplatesContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        MealTemplatesContent(
            state = MealTemplatesUiState(isLoading = false, rows = previewTemplateRows()),
            actions = MealTemplateListActions({}, {}, {}, {}, {}, {}, {}),
        )
    }
}

private fun previewTemplateRows(): List<TemplateRow> {
    fun template(id: Long, name: String, slot: MealSlot?, favorite: Boolean) = MealTemplate(
        id = id,
        name = name,
        defaultSlot = slot,
        note = null,
        isFavorite = favorite,
        useCount = 3,
        lastUsedAtMillis = null,
        archived = false,
        items = emptyList(),
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )
    return listOf(
        TemplateRow(
            template = template(1L, "Oatmeal with berries", MealSlot.BREAKFAST, true),
            totals = MacroTotals(kcal = 512.0, proteinG = 21.0, carbsG = 74.0, fatG = 13.0, fiberG = 9.0, sugarG = 18.0, satFatG = 3.0, saltG = 0.4),
            missingIngredients = false,
        ),
        TemplateRow(
            template = template(2L, "Post-match shake", MealSlot.POST_WORKOUT, false),
            totals = MacroTotals(kcal = 340.0, proteinG = 38.0, carbsG = 32.0, fatG = 6.0, fiberG = 2.0, sugarG = 22.0, satFatG = 2.0, saltG = 0.3),
            missingIngredients = true,
        ),
    )
}
