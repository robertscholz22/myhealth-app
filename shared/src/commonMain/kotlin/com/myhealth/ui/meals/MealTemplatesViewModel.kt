package com.myhealth.ui.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.repository.IngredientRepository
import com.myhealth.domain.repository.MealRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/**
 * Backs [MealTemplatesScreen] (PLAN §4.2 Meal templates, P4.4).
 *
 * The list needs nutrients, which templates do not carry: each emission of
 * [MealRepository.observeTemplates] resolves every referenced ingredient once
 * ([IngredientRepository.getByIds]) and prices the rows with [templateRow] / `MealMath`.
 */
class MealTemplatesViewModel(
    private val mealRepo: MealRepository,
    private val ingredientRepo: IngredientRepository,
    private val clock: PlatformClock,
) : ViewModel() {

    private val dialog = MutableStateFlow<LogNowDialog?>(null)
    private val message = MutableStateFlow<UiMessage?>(null)

    private val rows = mealRepo.observeTemplates().map { templates ->
        val ids = templates.flatMap { template -> template.items.map { it.ingredientId } }.distinct()
        val ingredients = ingredientRepo.getByIds(ids).associateBy { it.id }
        templates.map { templateRow(it, ingredients) }
    }

    val state: StateFlow<MealTemplatesUiState> =
        combine(rows, dialog, message) { list, logNow, msg ->
            MealTemplatesUiState(
                isLoading = false,
                rows = list,
                logTemplate = logNow?.template,
                logDay = logNow?.day ?: today(),
                logSlot = logNow?.slot ?: FALLBACK_SLOT,
                message = msg,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealTemplatesUiState())

    fun toggleFavorite(template: MealTemplate) {
        viewModelScope.launch {
            mealRepo.upsertTemplate(template.copy(isFavorite = !template.isFavorite))
        }
    }

    /** Opens the "Log now" dialog with today and the template's default slot pre-selected. */
    fun openLogNow(template: MealTemplate) {
        dialog.value = LogNowDialog(template, today(), defaultSlotFor(template))
    }

    fun setLogDay(day: Long) {
        dialog.update { it?.copy(day = day) }
    }

    fun setLogSlot(slot: MealSlot) {
        dialog.update { it?.copy(slot = slot) }
    }

    fun cancelLogNow() {
        dialog.value = null
    }

    fun confirmLogNow() {
        val current = dialog.value ?: return
        dialog.value = null
        viewModelScope.launch {
            val result = mealRepo.logTemplate(current.template.id, current.day, current.slot)
            message.value = when (result) {
                is Outcome.Ok -> UiMessage.of(Res.string.mealtpl_logged_now, current.template.name)
                is Outcome.Err -> UiMessage.of(Res.string.mealtpl_error_log_failed)
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun today(): Long = clock.today().toEpochDays()

    private data class LogNowDialog(val template: MealTemplate, val day: Long, val slot: MealSlot)
}
