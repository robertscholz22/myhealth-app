package com.myhealth.ui.ingredients

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.theme.MyHealthTheme
import kotlin.math.roundToInt

/** Searchable ingredient list (PLAN §4.2 Ingredients, P4.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientsScreen(
    onOpenIngredient: (Long) -> Unit,
    onNewIngredient: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVm { graph -> IngredientsViewModel(graph.ingredientRepo) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.ingredients_title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewIngredient) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.ingredients_new_content_description))
            }
        },
    ) { innerPadding ->
        IngredientsContent(
            state = state,
            onQueryChange = vm::setQuery,
            onShowArchivedChange = vm::setShowArchived,
            onToggleFavorite = vm::toggleFavorite,
            onOpenIngredient = onOpenIngredient,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

@Composable
private fun IngredientsContent(
    state: IngredientsUiState,
    onQueryChange: (String) -> Unit,
    onShowArchivedChange: (Boolean) -> Unit,
    onToggleFavorite: (Long, Boolean) -> Unit,
    onOpenIngredient: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(Res.string.ingredients_search_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.ingredients_show_archived), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = state.showArchived, onCheckedChange = onShowArchivedChange)
            }
        }
        if (state.items.isEmpty()) {
            EmptyState(
                title = stringResource(
                    if (state.showArchived) Res.string.ingredients_empty_archived_title else Res.string.ingredients_empty_title,
                ),
                message = stringResource(Res.string.ingredients_empty_message),
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(state.items, key = { it.id }) { ingredient ->
                    IngredientRow(
                        ingredient = ingredient,
                        onToggleFavorite = { fav -> onToggleFavorite(ingredient.id, fav) },
                        onClick = { onOpenIngredient(ingredient.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun IngredientRow(ingredient: Ingredient, onToggleFavorite: (Boolean) -> Unit, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(ingredient.name) },
        supportingContent = {
            val brand = ingredient.brand
            val basisLabel = ingredient.basisLabel()
            Text(if (brand.isNullOrBlank()) basisLabel else stringResource(Res.string.ingredients_brand_basis, brand, basisLabel))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(Res.string.ingredients_kcal_suffix, ingredient.kcal.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { onToggleFavorite(!ingredient.isFavorite) }) {
                    if (ingredient.isFavorite) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(Res.string.ingredients_unfavorite_content_description),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(Icons.Outlined.StarBorder, contentDescription = stringResource(Res.string.ingredients_favorite_content_description))
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Preview(showBackground = true, widthDp = 380, heightDp = 700)
@Composable
private fun IngredientsContentPreview() {
    val sample = listOf(
        previewIngredient(1, "Rolled oats", "Bob's Red Mill", MeasureBasis.PER_100G, 370.0, isFavorite = true),
        previewIngredient(2, "Egg", null, MeasureBasis.PER_PIECE, 78.0, pieceGrams = 50.0),
        previewIngredient(3, "Whole milk", "Alpro", MeasureBasis.PER_100ML, 64.0),
    )
    MyHealthTheme(dynamicColor = false) {
        IngredientsContent(
            state = IngredientsUiState(isLoading = false, items = sample),
            onQueryChange = {},
            onShowArchivedChange = {},
            onToggleFavorite = { _, _ -> },
            onOpenIngredient = {},
        )
    }
}

private fun previewIngredient(
    id: Long,
    name: String,
    brand: String?,
    basis: MeasureBasis,
    kcal: Double,
    pieceGrams: Double? = null,
    isFavorite: Boolean = false,
) = Ingredient(
    id = id,
    name = name,
    brand = brand,
    barcode = null,
    basis = basis,
    pieceGrams = pieceGrams,
    servingGrams = null,
    servingLabel = null,
    kcal = kcal,
    proteinG = null,
    carbsG = null,
    sugarG = null,
    fatG = null,
    satFatG = null,
    fiberG = null,
    saltG = null,
    sodiumG = null,
    isFavorite = isFavorite,
    source = "MANUAL",
    offProductJson = null,
    lastUsedAtMillis = null,
    useCount = 0,
    archived = false,
    createdAtMillis = 0L,
    updatedAtMillis = 0L,
)
