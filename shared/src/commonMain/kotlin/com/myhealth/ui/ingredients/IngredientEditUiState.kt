package com.myhealth.ui.ingredients

import com.myhealth.domain.util.EngineWarning
import com.myhealth.ui.common.UiMessage

/** ViewModel state for [IngredientEditScreen] (PLAN §4.2 Ingredient edit, P4.3). */
data class IngredientEditUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val draft: IngredientDraft = IngredientDraft(),
    val errors: Map<IngredientField, UiMessage> = emptyMap(),
    /** Live plausibility warnings from [com.myhealth.domain.engine.nutrition.NutritionValidator]
     * — informational, never blocks [errors]-free saves (§3.6.1 step 7 / P4.3). */
    val warnings: List<EngineWarning> = emptyList(),
    /** Warnings that came with a scan (P4.9) — shown once, alongside the live [warnings]. */
    val scanWarnings: List<EngineWarning> = emptyList(),
    /** An Open Food Facts lookup for the barcode in the form is in flight (P4.10). */
    val isLookingUp: Boolean = false,
    /** Mixes a local validation message with [com.myhealth.di.ScanMessages.of]'s plain `String`
     * (di layer, outside this package), so it stays a `String` rather than a [UiMessage]. */
    val lookupError: String? = null,
    val lookupNote: UiMessage? = null,
    val pendingDelete: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: UiMessage? = null,
    val loadError: UiMessage? = null,
    /** One-shot: the screen navigates back once either flips to `true`. */
    val saved: Boolean = false,
    val deleted: Boolean = false,
)
