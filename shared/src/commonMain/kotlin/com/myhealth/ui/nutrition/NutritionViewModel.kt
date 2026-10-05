package com.myhealth.ui.nutrition

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.minuteOfDay
import com.myhealth.data.time.timeZone
import com.myhealth.domain.model.MealLog
import com.myhealth.domain.model.MealLogItem
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.model.WaterLog
import com.myhealth.domain.repository.IngredientRepository
import com.myhealth.domain.repository.MealRepository
import com.myhealth.domain.repository.NutritionRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock

private const val KEY_DAY = "nutrition.day"

/**
 * Backs [NutritionScreen] (PLAN §4.2 Nutrition diary, P4.5).
 *
 * The shown day lives in the [SavedStateHandle] (seeded from the route, or from "today" for
 * `NutritionRoute`), so the ±1-day arrows survive process death. Meals come from
 * [MealRepository.observeDay], water from [NutritionRepository.observeWaterTotalMl] and the
 * header's target from [NutritionRepository.observeTarget].
 *
 * Opening a day calls [NutritionRepository.ensureTarget] for it (P4.12), so a target always exists
 * as soon as a profile does — the recompute worker keeps the rest of the window warm, but the user
 * never has to wait for it. `ensureTarget` is a no-op when the day's `inputsHash` is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NutritionViewModel(
    initialDay: Long,
    private val mealRepo: MealRepository,
    private val nutritionRepo: NutritionRepository,
    private val ingredientRepo: IngredientRepository,
    private val clock: PlatformClock,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val dayFlow: StateFlow<Long> = savedState.getStateFlow(KEY_DAY, initialDay)
    private val editing = MutableStateFlow<QuantityEdit?>(null)
    private val waterDraft = MutableStateFlow<WaterDraft?>(null)
    private val explanationExpanded = MutableStateFlow(false)
    private val message = MutableStateFlow<UiMessage?>(null)

    private val logs: Flow<List<MealLog>> = dayFlow.flatMapLatest { mealRepo.observeDay(it) }
    private val target: Flow<NutritionTarget?> = dayFlow.flatMapLatest { nutritionRepo.observeTarget(it) }
    private val water: Flow<Water> = dayFlow.flatMapLatest { day ->
        combine(
            nutritionRepo.observeWaterTotalMl(day),
            nutritionRepo.observeWaterLogs(day),
        ) { total, logs -> Water(total, logs) }
    }

    init {
        dayFlow.onEach { nutritionRepo.ensureTarget(it) }.launchIn(viewModelScope)
    }

    private val extras: Flow<Extras> =
        combine(editing, explanationExpanded, message, waterDraft) { edit, expanded, msg, draft ->
            Extras(edit, expanded, msg, draft)
        }

    val state: StateFlow<NutritionUiState> =
        combine(dayFlow, logs, target, water, extras) { day, dayLogs, dayTarget, dayWater, extra ->
            NutritionUiState(
                isLoading = false,
                day = day,
                target = dayTarget,
                sections = sectionsOf(dayLogs),
                intake = intakeOf(dayLogs),
                editing = extra.editing,
                explanationExpanded = extra.explanationExpanded,
                waterMl = dayWater.totalMl,
                waterLogs = dayWater.logs,
                waterDraftMl = extra.waterDraft?.amountMl,
                waterDialogOpen = extra.waterDraft != null,
                message = extra.message,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            NutritionUiState(day = dayFlow.value),
        )

    fun showPreviousDay() {
        savedState[KEY_DAY] = dayFlow.value - 1
    }

    fun showNextDay() {
        savedState[KEY_DAY] = dayFlow.value + 1
    }

    fun setDay(day: Long) {
        savedState[KEY_DAY] = day
    }

    fun toggleExplanation() {
        explanationExpanded.value = !explanationExpanded.value
    }

    /** Opens the quantity dialog; the unit choices come from the item's ingredient when it still
     * exists, otherwise the item keeps the unit it was logged with. */
    fun editItem(item: MealLogItem) {
        viewModelScope.launch {
            val ingredient = item.ingredientId?.let { ingredientRepo.getById(it) }
            editing.value = QuantityEdit(
                itemId = item.id,
                name = item.nameSnapshot,
                quantity = item.quantity,
                unit = item.unit,
                units = ingredient?.let { validUnitsFor(it.basis, it) } ?: listOf(item.unit),
            )
        }
    }

    fun setEditQuantity(quantity: Double?) {
        editing.value = editing.value?.copy(quantity = quantity)
    }

    fun setEditUnit(unit: QuantityUnit) {
        editing.value = editing.value?.copy(unit = unit)
    }

    fun cancelEdit() {
        editing.value = null
    }

    fun confirmEdit() {
        val edit = editing.value ?: return
        val quantity = edit.quantity
        if (quantity == null || quantity <= 0.0) {
            message.value = UiMessage.of(Res.string.quantity_error_zero)
            return
        }
        editing.value = null
        viewModelScope.launch {
            if (mealRepo.updateItemQuantity(edit.itemId, quantity, edit.unit) is Outcome.Err) {
                message.value = UiMessage.of(Res.string.diary_error_update_item)
            }
        }
    }

    fun deleteItem(itemId: Long) {
        viewModelScope.launch {
            message.value = when (mealRepo.deleteItem(itemId)) {
                is Outcome.Ok -> UiMessage.of(Res.string.diary_item_removed)
                is Outcome.Err -> UiMessage.of(Res.string.diary_error_remove_item)
            }
        }
    }

    fun deleteLog(logId: Long) {
        viewModelScope.launch {
            message.value = when (mealRepo.deleteLog(logId)) {
                is Outcome.Ok -> UiMessage.of(Res.string.diary_meal_removed)
                is Outcome.Err -> UiMessage.of(Res.string.diary_error_remove_meal)
            }
        }
    }

    /** "Copy yesterday" (§4.2): copies the previous day's logs onto the shown day verbatim. */
    fun copyYesterday() {
        val day = dayFlow.value
        viewModelScope.launch {
            message.value = when (mealRepo.copyDay(day - 1, day)) {
                is Outcome.Ok -> UiMessage.of(Res.string.diary_copied_yesterday)
                is Outcome.Err -> UiMessage.of(Res.string.diary_error_copy_yesterday)
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    // ---- water (P4.13) -------------------------------------------------------------------------

    /** The +250 / +500 ml quick buttons and the confirmed custom amount all land here. */
    fun addWater(ml: Int) {
        val day = dayFlow.value
        viewModelScope.launch {
            val minuteOfDay = minuteOfDay(clock.millis(), clock.timeZone)
            if (nutritionRepo.addWater(day, ml, minuteOfDay) is Outcome.Err) {
                message.value = UiMessage.of(Res.string.water_error_log_drink)
            }
        }
    }

    fun openWaterDialog() {
        waterDraft.value = WaterDraft(amountMl = null)
    }

    fun setWaterDraft(ml: Double?) {
        waterDraft.value = WaterDraft(amountMl = ml)
    }

    fun cancelWaterDialog() {
        waterDraft.value = null
    }

    fun confirmWaterDialog() {
        val amount = waterDraft.value?.amountMl
        if (amount == null || amount <= 0.0) {
            message.value = UiMessage.of(Res.string.water_error_amount_zero)
            return
        }
        waterDraft.value = null
        addWater(amount.toInt())
    }

    fun deleteWater(id: Long) {
        viewModelScope.launch {
            if (nutritionRepo.deleteWater(id) is Outcome.Err) {
                message.value = UiMessage.of(Res.string.water_error_remove_drink)
            }
        }
    }

    private data class Water(val totalMl: Int, val logs: List<WaterLog>)

    private data class WaterDraft(val amountMl: Double?)

    private data class Extras(
        val editing: QuantityEdit?,
        val explanationExpanded: Boolean,
        val message: UiMessage?,
        val waterDraft: WaterDraft?,
    )
}
