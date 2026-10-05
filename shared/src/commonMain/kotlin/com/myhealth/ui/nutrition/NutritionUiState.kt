package com.myhealth.ui.nutrition

import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealLog
import com.myhealth.domain.model.MealLogItem
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.model.WaterLog
import com.myhealth.domain.util.epochDayDate
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.fmtDecimal
import kotlinx.datetime.LocalDate

/**
 * Message shown in place of the target header when the day has no snapshot. With a profile in
 * place the diary asks for one on open (`ensureTarget`, P4.12), so this is only seen before
 * onboarding is finished or while the very first computation is in flight.
 */
const val NO_TARGET_MESSAGE: String = "Targets appear once your profile is set up."

/**
 * One meal slot of the diary: every log filed under it, and their summed snapshots. Slots are
 * always rendered in `MealSlot` declaration order (§2.1), empty ones included, so the "+ Add"
 * affordance sits in the same place every day.
 */
data class SlotSection(
    val slot: MealSlot,
    val logs: List<MealLog>,
    val totals: MacroTotals,
) {
    val isEmpty: Boolean get() = logs.all { it.items.isEmpty() }
}

/** The in-place quantity edit dialog of §4.2 ("edit/delete item"). */
data class QuantityEdit(
    val itemId: Long,
    val name: String,
    val quantity: Double?,
    val unit: QuantityUnit,
    val units: List<QuantityUnit>,
)

/** ViewModel state for [NutritionScreen] (PLAN §4.2 Nutrition diary, P4.5). */
data class NutritionUiState(
    val isLoading: Boolean = true,
    val day: Long = 0L,
    val target: NutritionTarget? = null,
    val sections: List<SlotSection> = emptyList(),
    val intake: MacroTotals = MacroTotals.ZERO,
    val editing: QuantityEdit? = null,
    val explanationExpanded: Boolean = false,
    /** Sum of the day's `water_log` rows (P4.13). */
    val waterMl: Int = 0,
    val waterLogs: List<WaterLog> = emptyList(),
    /** The custom-amount dialog of the water card: `null` when closed. */
    val waterDraftMl: Double? = null,
    val waterDialogOpen: Boolean = false,
    val message: UiMessage? = null,
) {
    val date: LocalDate get() = day.epochDayDate()

    val hasAnyMeal: Boolean get() = sections.any { !it.isEmpty }
}

/** Sums the denormalised snapshots of [items] — they are already absolute values (§2.2.5). */
fun totalsOf(items: List<MealLogItem>): MacroTotals = items.fold(MacroTotals.ZERO) { acc, item ->
    acc + MacroTotals(
        kcal = item.kcal,
        proteinG = item.proteinG,
        carbsG = item.carbsG,
        fatG = item.fatG,
        fiberG = item.fiberG,
        sugarG = item.sugarG,
        satFatG = item.satFatG,
        saltG = item.saltG,
    )
}

/** Groups a day's logs into one [SlotSection] per [MealSlot], in enum order. */
fun sectionsOf(logs: List<MealLog>): List<SlotSection> = MealSlot.entries.map { slot ->
    val slotLogs = logs.filter { it.slot == slot }
    SlotSection(
        slot = slot,
        logs = slotLogs,
        totals = totalsOf(slotLogs.flatMap { it.items }),
    )
}

/** A day's intake: the sum of every logged item's snapshot (§3.7 "Σ over meals"). */
fun intakeOf(logs: List<MealLog>): MacroTotals = totalsOf(logs.flatMap { it.items })

/**
 * "380 kcal left" / "210 kcal over" for the header, or `null` without a target snapshot. Energy
 * is rounded half-up (amendment A4 / §3.7: kcal displays as an `Int`).
 */
fun remainingLabel(target: NutritionTarget?, intake: MacroTotals): String? {
    if (target == null) return null
    val remaining = roundHalfUp(target.kcal - intake.kcal)
    return if (remaining >= 0) "$remaining kcal left" else "${-remaining} kcal over"
}

/** Energy ring fill in `[0, 1]`, `0` without a target. */
fun energyFraction(target: NutritionTarget?, intake: MacroTotals): Float {
    val kcal = target?.kcal ?: return 0f
    if (kcal <= 0) return 0f
    return (intake.kcal / kcal).coerceIn(0.0, 1.0).toFloat()
}

/** "142 g · 3 items" style line for one item row's quantity. */
fun quantityLabel(quantity: Double, unit: QuantityUnit): String {
    val amount = if (quantity == quantity.toLong().toDouble()) {
        quantity.toLong().toString()
    } else {
        fmtDecimal(quantity, 1)
    }
    return "$amount ${unit.label()}"
}

/**
 * The warning lines of the "Why this target?" expander — the engine's `EngineWarning` messages
 * (§1.5), prefixed so they read as caveats rather than as part of the explanation.
 */
fun warningLines(target: NutritionTarget?): List<String> =
    target?.warnings?.map { "! ${it.message}" } ?: emptyList()

/** Water progress in `[0, 1]` against the day's `waterMl` target; `0` without a target. */
fun waterFraction(target: NutritionTarget?, totalMl: Int): Float {
    val goal = target?.waterMl ?: return 0f
    if (goal <= 0) return 0f
    return (totalMl.toDouble() / goal).coerceIn(0.0, 1.0).toFloat()
}

/** A litre in millilitres — below it the water card counts in ml rather than in tenths (NOTE-3). */
private const val ML_PER_LITRE: Int = 1000

/** "750 ml" under a litre, "2.7 l" from a litre up. */
fun waterAmountLabel(ml: Int): String =
    if (ml < ML_PER_LITRE) "$ml ml" else "${fmtDecimal(ml / 1000.0, 1)} l"

/**
 * "1.5 / 2.8 l" for the water card, or "750 ml / 2.7 l" while either side is under a litre —
 * one decimal would round 750 ml to "0.8 l" and 50 ml to "0.1 l" (NOTE-3).
 */
fun waterLabel(target: NutritionTarget?, totalMl: Int): String {
    val goal = target?.waterMl ?: return waterAmountLabel(totalMl)
    if (totalMl >= ML_PER_LITRE && goal >= ML_PER_LITRE) {
        return "${fmtDecimal(totalMl / 1000.0, 1)} / ${fmtDecimal(goal / 1000.0, 1)} l"
    }
    return "${waterAmountLabel(totalMl)} / ${waterAmountLabel(goal)}"
}

/** Half-up rounding (amendment A4) — `kotlin.math.round` is half-to-even. */
internal fun roundHalfUp(value: Double): Int = kotlin.math.floor(value + 0.5).toInt()
