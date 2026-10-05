package com.myhealth.ui.nutrition

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealLog
import com.myhealth.domain.model.MealLogItem
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.model.WaterLog
import com.myhealth.domain.util.EngineWarning
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The pure parts of the diary state (PLAN P4.5): slot grouping, totals, header labels, and the
 * water-card labels of P4.13. The water/quantity labels format through `fmtDecimal` (POLISH-12),
 * which follows `Locale.getDefault()`; pinned to `Locale.US` here so the verbatim assertions below
 * are locale-independent.
 */
class NutritionUiStateTest {

    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun water_progress_is_the_day_total_over_the_target() {
        val target = target(kcal = 2230).copy(waterMl = 3000)

        assertThat(waterFraction(target, 0)).isEqualTo(0f)
        assertThat(waterFraction(target, 1500)).isWithin(1e-6f).of(0.5f)
        // Overshooting never overfills the bar, and without a target there is nothing to fill.
        assertThat(waterFraction(target, 4000)).isEqualTo(1f)
        assertThat(waterFraction(null, 1500)).isEqualTo(0f)
    }

    @Test
    fun water_labels_read_in_litres() {
        val target = target(kcal = 2230)

        assertThat(waterLabel(target, 1500)).isEqualTo("1.5 / 2.8 l")
        assertThat(waterLabel(null, 1500)).isEqualTo("1.5 l")
        assertThat(waterRemainingLabel(target, 1500)).isEqualTo("1.3 l left")
        assertThat(waterRemainingLabel(target, 2800)).isEqualTo("Target reached")
        assertThat(waterRemainingLabel(null, 0)).isEmpty()
    }

    @Test
    fun water_entry_times_render_as_hh_mm() {
        assertThat(waterEntryTime(WaterLog(1L, 20_000L, 7 * 60 + 20, 250))).isEqualTo("07:20")
        assertThat(waterEntryTime(WaterLog(2L, 20_000L, null, 250))).isEqualTo("\u2014")
    }

    @Test
    fun warning_lines_are_prefixed_engine_messages() {
        val warned = target(kcal = 2230).copy(
            warnings = listOf(
                EngineWarning(EngineWarningCode.MISSING_WEIGHT, "No weight measured."),
            ),
        )

        assertThat(warningLines(warned)).containsExactly("! No weight measured.")
        assertThat(warningLines(target(kcal = 2230))).isEmpty()
        assertThat(warningLines(null)).isEmpty()
    }


    @Test
    fun sections_cover_every_slot_in_declaration_order() {
        val sections = sectionsOf(listOf(log(1L, MealSlot.DINNER, items = listOf(item(1L, 500.0, 30.0)))))

        assertThat(sections.map { it.slot }).isEqualTo(MealSlot.entries.toList())
        assertThat(sections.first { it.slot == MealSlot.DINNER }.isEmpty).isFalse()
        assertThat(sections.first { it.slot == MealSlot.BREAKFAST }.isEmpty).isTrue()
    }

    @Test
    fun per_slot_totals_sum_the_stored_snapshots() {
        val logs = listOf(
            log(1L, MealSlot.LUNCH, items = listOf(item(1L, 500.0, 30.0), item(2L, 250.5, 12.5))),
            log(2L, MealSlot.LUNCH, items = listOf(item(3L, 100.0, 5.0))),
            log(3L, MealSlot.DINNER, items = listOf(item(4L, 700.0, 40.0))),
        )

        val sections = sectionsOf(logs)

        val lunch = sections.first { it.slot == MealSlot.LUNCH }
        assertThat(lunch.logs).hasSize(2)
        assertThat(lunch.totals.kcal).isWithin(1e-9).of(850.5)
        assertThat(lunch.totals.proteinG).isWithin(1e-9).of(47.5)
        assertThat(intakeOf(logs).kcal).isWithin(1e-9).of(1550.5)
    }

    @Test
    fun remaining_reports_both_left_and_over() {
        val target = target(kcal = 2230)

        assertThat(remainingLabel(target, MacroTotals.ZERO.copy(kcal = 1850.0))).isEqualTo("380 kcal left")
        assertThat(remainingLabel(target, MacroTotals.ZERO.copy(kcal = 2440.0))).isEqualTo("210 kcal over")
        assertThat(remainingLabel(null, MacroTotals.ZERO)).isNull()
    }

    @Test
    fun the_energy_fraction_is_clamped_and_zero_without_a_target() {
        val target = target(kcal = 2000)

        assertThat(energyFraction(target, MacroTotals.ZERO.copy(kcal = 1000.0))).isWithin(1e-6f).of(0.5f)
        assertThat(energyFraction(target, MacroTotals.ZERO.copy(kcal = 4000.0))).isEqualTo(1f)
        assertThat(energyFraction(null, MacroTotals.ZERO.copy(kcal = 1000.0))).isEqualTo(0f)
    }

    @Test
    fun quantity_labels_drop_a_trailing_zero_decimal() {
        assertThat(quantityLabel(100.0, QuantityUnit.G)).isEqualTo("100 g")
        assertThat(quantityLabel(1.5, QuantityUnit.PIECE)).isEqualTo("1.5 piece")
        assertThat(quantityLabel(250.0, QuantityUnit.ML)).isEqualTo("250 ml")
    }

    @Test
    fun the_state_knows_whether_the_day_has_any_meal() {
        val empty = NutritionUiState(isLoading = false, day = 20_000L, sections = sectionsOf(emptyList()))
        val filled = empty.copy(
            sections = sectionsOf(listOf(log(1L, MealSlot.BREAKFAST, items = listOf(item(1L, 300.0, 10.0))))),
        )

        assertThat(empty.hasAnyMeal).isFalse()
        assertThat(filled.hasAnyMeal).isTrue()
        assertThat(NO_TARGET_MESSAGE).contains("profile")
    }

    private fun log(id: Long, slot: MealSlot, items: List<MealLogItem>) = MealLog(
        id = id,
        day = 20_000L,
        atMinuteOfDay = null,
        slot = slot,
        name = null,
        templateId = null,
        note = null,
        items = items.map { it.copy(mealLogId = id) },
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    private fun item(id: Long, kcal: Double, protein: Double) = MealLogItem(
        id = id,
        mealLogId = 0L,
        ingredientId = id,
        nameSnapshot = "Item $id",
        quantity = 100.0,
        unit = QuantityUnit.G,
        kcal = kcal,
        proteinG = protein,
        carbsG = 10.0,
        sugarG = 2.0,
        fatG = 5.0,
        satFatG = 1.0,
        fiberG = 3.0,
        saltG = 0.1,
    )

    private fun target(kcal: Int) = NutritionTarget(
        day = 20_000L,
        kcal = kcal,
        proteinG = 150,
        carbsG = 250,
        fatG = 70,
        fiberG = 30,
        sugarCapG = 60,
        satFatCapG = 24,
        saltG = 6.0,
        waterMl = 2800,
        bmrKcal = 1720,
        tdeeKcal = 2480,
        dayType = DayType.TRAINING,
        explanation = "",
        warnings = emptyList(),
        inputsHash = "test",
        computedAtMillis = 0L,
    )
}
