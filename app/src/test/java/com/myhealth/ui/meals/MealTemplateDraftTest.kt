package com.myhealth.ui.meals

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.MealTemplateItem
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.testutil.Fixtures
import org.junit.Test

/** The pure template-editor form logic (PLAN §4.2 Meal template edit, P4.4). */
class MealTemplateDraftTest {

    private val oats = ingredient(1L, "Rolled oats", MeasureBasis.PER_100G)
    private val egg = ingredient(3L, "Egg", MeasureBasis.PER_PIECE, pieceGrams = 50.0)
    private val clock = Fixtures.fixedClock("2026-09-12T12:00:00Z")

    @Test
    fun a_blank_name_is_an_error() {
        val errors = validateTemplate(MealTemplateDraft(name = "  ", items = listOf(itemDraftFor(oats))))

        assertThat(errors.keys).containsExactly(TemplateField.NAME)
    }

    @Test
    fun a_template_without_items_is_an_error() {
        val errors = validateTemplate(MealTemplateDraft(name = "Oatmeal"))

        assertThat(errors[TemplateField.ITEMS]).isEqualTo("Add at least one ingredient.")
    }

    @Test
    fun a_zero_quantity_is_an_error() {
        val draft = MealTemplateDraft(
            name = "Oatmeal",
            items = listOf(itemDraftFor(oats).copy(quantity = 0.0)),
        )

        assertThat(errors(draft)).contains("quantity above zero")
    }

    @Test
    fun a_valid_draft_has_no_errors() {
        val draft = MealTemplateDraft(name = "Oatmeal", items = listOf(itemDraftFor(oats)))

        assertThat(validateTemplate(draft)).isEmpty()
    }

    @Test
    fun live_totals_price_the_resolved_rows_only() {
        val draft = MealTemplateDraft(
            name = "Oatmeal",
            items = listOf(
                itemDraftFor(oats).copy(quantity = 80.0),
                TemplateItemDraft(9L, "Gone", 100.0, QuantityUnit.G, ingredient = null),
            ),
        )

        assertThat(draft.totals().kcal).isWithin(1e-9).of(296.0)
    }

    @Test
    fun new_item_rows_default_to_the_ingredients_natural_amount() {
        assertThat(itemDraftFor(oats).unit).isEqualTo(QuantityUnit.G)
        assertThat(itemDraftFor(oats).quantity).isEqualTo(100.0)
        assertThat(itemDraftFor(egg).unit).isEqualTo(QuantityUnit.PIECE)
        assertThat(itemDraftFor(egg).quantity).isEqualTo(1.0)
    }

    @Test
    fun saving_trims_the_name_and_renumbers_the_rows() {
        val draft = MealTemplateDraft(
            id = 7L,
            name = "  Oatmeal  ",
            defaultSlot = MealSlot.BREAKFAST,
            items = listOf(itemDraftFor(egg), itemDraftFor(oats)),
            createdAtMillis = 111L,
        )

        val template = draft.toMealTemplate(clock)

        assertThat(template.name).isEqualTo("Oatmeal")
        assertThat(template.items.map { it.ingredientId }).containsExactly(egg.id, oats.id).inOrder()
        assertThat(template.items.map { it.sortOrder }).containsExactly(0, 1).inOrder()
        assertThat(template.createdAtMillis).isEqualTo(111L)
        assertThat(template.updatedAtMillis).isEqualTo(clock.millis())
    }

    @Test
    fun loading_a_template_resolves_each_item_and_flags_the_missing_ones() {
        val template = MealTemplate(
            id = 7L,
            name = "Oatmeal",
            defaultSlot = MealSlot.BREAKFAST,
            note = "note",
            isFavorite = true,
            useCount = 4,
            lastUsedAtMillis = 99L,
            archived = false,
            items = listOf(
                MealTemplateItem(1L, 7L, oats.id, 80.0, QuantityUnit.G, sortOrder = 1),
                MealTemplateItem(2L, 7L, 42L, 1.0, QuantityUnit.PIECE, sortOrder = 0),
            ),
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
        )

        val draft = templateDraftOf(template, mapOf(oats.id to oats))

        assertThat(draft.items.map { it.name }).containsExactly("Ingredient #42", "Rolled oats").inOrder()
        assertThat(draft.items.first().ingredient).isNull()
        assertThat(draft.note).isEqualTo("note")
        assertThat(draft.isFavorite).isTrue()
    }

    @Test
    fun p24_servings_must_be_above_zero_and_blank_is_an_error() {
        val valid = MealTemplateDraft(name = "Oatmeal", items = listOf(itemDraftFor(oats)))

        assertThat(validateTemplate(valid)).isEmpty()
        assertThat(validateTemplate(valid.copy(servings = 0.0)).keys).containsExactly(TemplateField.SERVINGS)
        assertThat(validateTemplate(valid.copy(servings = null)).keys).containsExactly(TemplateField.SERVINGS)
        assertThat(validateTemplate(valid.copy(servings = 2.5))).isEmpty()
    }

    @Test
    fun p24_per_serving_divides_the_live_totals_by_the_servings() {
        val draft = MealTemplateDraft(name = "Oatmeal", items = listOf(itemDraftFor(oats).copy(quantity = 400.0)), servings = 4.0)

        // 400 g at 370 kcal / 100 g = 1480 kcal for the recipe, 370 kcal a serving.
        assertThat(draft.totals().kcal).isWithin(1e-9).of(1480.0)
        assertThat(draft.perServing(draft.totals())!!.kcal).isWithin(1e-9).of(370.0)
        assertThat(draft.copy(servings = null).perServing(draft.totals())).isNull()
    }

    @Test
    fun p24_servings_survive_load_and_save() {
        val template = MealTemplate(
            id = 7L, name = "Chili", defaultSlot = null, note = null, isFavorite = false, useCount = 0,
            lastUsedAtMillis = null, archived = false,
            items = listOf(MealTemplateItem(1L, 7L, oats.id, 400.0, QuantityUnit.G, sortOrder = 0)),
            createdAtMillis = 1L, updatedAtMillis = 2L, servings = 2.5,
        )

        val draft = templateDraftOf(template, mapOf(oats.id to oats))

        assertThat(draft.servings).isEqualTo(2.5)
        assertThat(draft.copy(servings = 6.0).toMealTemplate(clock).servings).isEqualTo(6.0)
        assertThat(MealTemplateDraft().servings).isEqualTo(1.0)
    }

    @Test
    fun p24_the_servings_field_shows_as_many_decimals_as_the_number_has() {
        assertThat(servingsDecimals(4.0)).isEqualTo(0)
        assertThat(servingsDecimals(2.5)).isEqualTo(1)
        assertThat(servingsDecimals(1.25)).isEqualTo(2)
        assertThat(servingsDecimals(null)).isEqualTo(0)
    }

    @Test
    fun p24_a_list_row_and_the_log_preview_scale_the_whole_recipe() {
        val template = MealTemplate(
            id = 7L, name = "Chili", defaultSlot = null, note = null, isFavorite = false, useCount = 0,
            lastUsedAtMillis = null, archived = false,
            items = listOf(MealTemplateItem(1L, 7L, oats.id, 400.0, QuantityUnit.G, sortOrder = 0)),
            createdAtMillis = 1L, updatedAtMillis = 2L, servings = 4.0,
        )
        val row = templateRow(template, mapOf(oats.id to oats))

        assertThat(row.perServing.kcal).isWithin(1e-9).of(370.0)
        val state = MealTemplatesUiState(isLoading = false, rows = listOf(row), logTemplate = template, logServings = 1.5)
        assertThat(state.logPreview!!.kcal).isWithin(1e-9).of(555.0)
        assertThat(state.copy(logServings = null).logPreview).isNull()
        assertThat(state.copy(logServings = 0.0).logPreview).isNull()
    }

    private fun errors(draft: MealTemplateDraft): String = validateTemplate(draft).values.joinToString()

    private fun ingredient(id: Long, name: String, basis: MeasureBasis, pieceGrams: Double? = null) = Ingredient(
        id = id,
        name = name,
        brand = null,
        barcode = null,
        basis = basis,
        pieceGrams = pieceGrams,
        servingGrams = null,
        servingLabel = null,
        kcal = 370.0,
        proteinG = 13.0,
        carbsG = 60.0,
        sugarG = 1.0,
        fatG = 7.0,
        satFatG = 1.0,
        fiberG = 10.0,
        saltG = 0.02,
        sodiumG = 0.008,
        isFavorite = false,
        source = "MANUAL",
        offProductJson = null,
        lastUsedAtMillis = null,
        useCount = 0,
        archived = false,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )
}
