package com.myhealth.ui.nutrition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MealSlot
import com.myhealth.ui.calendar.displayName
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.ingredients.basisLabel
import com.myhealth.ui.theme.MyHealthTheme
import kotlin.math.roundToInt

/**
 * Add food to one day + slot (PLAN §4.2 Add food, P4.6). Tabs: Recents · Favorites · Search ·
 * Templates · Scan — picking the Scan tab navigates to `ScanRoute` (the camera itself is P4.8)
 * rather than switching the list, so the tab row stays the single entry point for "where does the
 * next item come from".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodScreen(
    epochDay: Long,
    slot: MealSlot,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onNewIngredient: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVm { graph ->
        AddFoodViewModel(epochDay, slot, graph.mealRepo, graph.ingredientRepo)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.added) { if (state.added) onBack() }
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
                title = { Text(stringResource(Res.string.addfood_title_add_to, slot.displayName())) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onNewIngredient) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.addfood_new_ingredient_cd))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        AddFoodContent(
            state = state,
            onTabSelected = { tab -> if (tab == AddFoodTab.SCAN) onScan() else vm.setTab(tab) },
            onQueryChange = vm::setQuery,
            onSelectIngredient = vm::select,
            onLogTemplate = vm::logTemplate,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }

    state.selected?.let { ingredient ->
        QuantityEditor(
            ingredient = ingredient,
            quantity = state.quantity,
            unit = state.unit,
            units = state.validUnits,
            chips = state.quickChips,
            preview = state.preview,
            canAdd = state.canAdd,
            onQuantityChange = vm::setQuantity,
            onUnitChange = vm::setUnit,
            onChipClick = vm::applyChip,
            onAdd = vm::add,
            onDismiss = vm::cancelSelection,
        )
    }
}

@Composable
private fun AddFoodContent(
    state: AddFoodUiState,
    onTabSelected: (AddFoodTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectIngredient: (Ingredient) -> Unit,
    onLogTemplate: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ScrollableTabRow(selectedTabIndex = state.tab.ordinal) {
            AddFoodTab.entries.forEach { tab ->
                Tab(
                    selected = tab == state.tab,
                    onClick = { onTabSelected(tab) },
                    text = { Text(tab.label()) },
                )
            }
        }
        if (state.tab == AddFoodTab.SEARCH) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(Res.string.addfood_search_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        when (state.tab) {
            AddFoodTab.TEMPLATES -> TemplateList(state = state, onLogTemplate = onLogTemplate)
            else -> IngredientList(state = state, onSelect = onSelectIngredient)
        }
    }
}

@Composable
private fun IngredientList(state: AddFoodUiState, onSelect: (Ingredient) -> Unit) {
    if (state.ingredients.isEmpty()) {
        EmptyState(
            title = when (state.tab) {
                AddFoodTab.RECENTS -> stringResource(Res.string.addfood_empty_recents_title)
                AddFoodTab.FAVORITES -> stringResource(Res.string.addfood_empty_favorites_title)
                else -> stringResource(Res.string.addfood_empty_search_title)
            },
            message = stringResource(Res.string.addfood_empty_ingredients_message),
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.ingredients, key = { it.id }) { ingredient ->
            ListItem(
                headlineContent = { Text(ingredient.name) },
                supportingContent = {
                    val brand = ingredient.brand
                    val basis = ingredient.basisLabel()
                    Text(if (brand.isNullOrBlank()) basis else "$brand · $basis")
                },
                trailingContent = {
                    Text(
                        stringResource(Res.string.addfood_kcal_value, ingredient.kcal.roundToInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                modifier = Modifier.fillMaxWidth().clickable { onSelect(ingredient) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun TemplateList(state: AddFoodUiState, onLogTemplate: (Long) -> Unit) {
    if (state.templates.isEmpty()) {
        EmptyState(
            title = stringResource(Res.string.addfood_empty_templates_title),
            message = stringResource(Res.string.addfood_empty_templates_message),
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.templates, key = { it.id }) { template ->
            ListItem(
                headlineContent = { Text(template.name) },
                supportingContent = { Text(stringResource(Res.string.addfood_template_item_count, template.items.size)) },
                modifier = Modifier.fillMaxWidth().clickable { onLogTemplate(template.id) },
            )
            HorizontalDivider()
        }
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun AddFoodContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        AddFoodContent(
            state = AddFoodUiState(day = 20_000L, slot = MealSlot.LUNCH, tab = AddFoodTab.RECENTS),
            onTabSelected = {},
            onQueryChange = {},
            onSelectIngredient = {},
            onLogTemplate = {},
        )
    }
}
