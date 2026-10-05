package com.myhealth.ui.ingredients

import androidx.compose.runtime.Composable
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFacts
import com.myhealth.resources.*
import com.myhealth.ui.camera.ScanDraft
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.stringResource
import com.myhealth.ui.common.fmtDecimal
import com.myhealth.data.time.PlatformClock

/** Field identity for [validate] errors (mirrors `EventField`'s pattern, §1.4/P3.6). */
enum class IngredientField { NAME, BASIS, PIECE_GRAMS, KCAL, PROTEIN, CARBS, FAT, SUGAR, SAT_FAT, FIBER, SALT }

/**
 * The ingredient editor's form state (PLAN §4.2 Ingredient edit, P4.3), before it becomes an
 * [Ingredient]. [sodiumG] is never edited directly — it is always `saltG / 2.5` (§3.6.1 step 5)
 * and shown read-only in the editor.
 */
data class IngredientDraft(
    val id: Long = 0L,
    val name: String = "",
    val brand: String = "",
    val barcode: String = "",
    val basis: MeasureBasis = MeasureBasis.PER_100G,
    val pieceGrams: Double? = null,
    val servingGrams: Double? = null,
    val servingLabel: String = "",
    val kcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val sugarG: Double? = null,
    val fatG: Double? = null,
    val satFatG: Double? = null,
    val fiberG: Double? = null,
    val saltG: Double? = null,
    val isFavorite: Boolean = false,
    val source: String = "MANUAL",
    val offProductJson: String? = null,
    val lastUsedAtMillis: Long? = null,
    val useCount: Int = 0,
    val archived: Boolean = false,
    val createdAtMillis: Long = 0L,
) {
    /** `saltG / 2.5` (§3.6.1 step 5) — the read-only value shown next to the salt field. */
    val sodiumG: Double? get() = saltG?.div(2.5)
}

/** The unit suffix the basis selector switches to (§4.2 Ingredient edit: "basis selector
 * switching unit suffixes"). */
@Composable
fun MeasureBasis.unitSuffix(): String = when (this) {
    MeasureBasis.PER_100G -> stringResource(Res.string.ingredient_unit_suffix_100g)
    MeasureBasis.PER_100ML -> stringResource(Res.string.ingredient_unit_suffix_100ml)
    MeasureBasis.PER_PIECE -> stringResource(Res.string.ingredient_unit_suffix_piece)
}

/** The list-row basis label (§4.2 Ingredients: "per 100 g" / "per 100 ml" / "per piece (30 g)"). */
@Composable
fun Ingredient.basisLabel(): String = when (basis) {
    MeasureBasis.PER_100G -> stringResource(Res.string.ingredient_basis_label_100g)
    MeasureBasis.PER_100ML -> stringResource(Res.string.ingredient_basis_label_100ml)
    MeasureBasis.PER_PIECE -> pieceGrams?.let {
        stringResource(Res.string.ingredient_basis_label_piece_with_grams, it.trimZeros())
    } ?: stringResource(Res.string.ingredient_basis_label_piece)
}

private fun Double.trimZeros(): String =
    if (this == this.toLong().toDouble()) this.toLong().toString() else fmtDecimal(this, 1)

/**
 * Pure validation (unit-tested in `IngredientDraftTest`, P4.3): name required; kcal, protein,
 * carbs and fat required and non-negative (mirrors `RoomIngredientRepository`'s required-field
 * check, §2.2.5); `PER_PIECE` requires `pieceGrams`; every other numeric field, when present,
 * must be non-negative.
 */
fun validate(draft: IngredientDraft): Map<IngredientField, UiMessage> {
    val errors = mutableMapOf<IngredientField, UiMessage>()

    if (draft.name.isBlank()) {
        errors[IngredientField.NAME] = UiMessage.of(Res.string.ingredient_error_name_required)
    }

    val kcal = draft.kcal
    if (kcal == null || kcal < 0.0) {
        errors[IngredientField.KCAL] = UiMessage.of(Res.string.ingredient_error_kcal_required)
    }

    val protein = draft.proteinG
    if (protein == null || protein < 0.0) {
        errors[IngredientField.PROTEIN] = UiMessage.of(Res.string.ingredient_error_protein_required)
    }

    val carbs = draft.carbsG
    if (carbs == null || carbs < 0.0) {
        errors[IngredientField.CARBS] = UiMessage.of(Res.string.ingredient_error_carbs_required)
    }

    val fat = draft.fatG
    if (fat == null || fat < 0.0) {
        errors[IngredientField.FAT] = UiMessage.of(Res.string.ingredient_error_fat_required)
    }

    if (draft.basis == MeasureBasis.PER_PIECE && draft.pieceGrams == null) {
        errors[IngredientField.PIECE_GRAMS] = UiMessage.of(Res.string.ingredient_error_piece_grams_required)
    }

    if ((draft.sugarG ?: 0.0) < 0.0) errors[IngredientField.SUGAR] = UiMessage.of(Res.string.ingredient_error_sugar_negative)
    if ((draft.satFatG ?: 0.0) < 0.0) {
        errors[IngredientField.SAT_FAT] = UiMessage.of(Res.string.ingredient_error_sat_fat_negative)
    }
    if ((draft.fiberG ?: 0.0) < 0.0) errors[IngredientField.FIBER] = UiMessage.of(Res.string.ingredient_error_fiber_negative)
    if ((draft.saltG ?: 0.0) < 0.0) errors[IngredientField.SALT] = UiMessage.of(Res.string.ingredient_error_salt_negative)

    return errors
}

/**
 * Builds the [Ingredient] to upsert. Only called once [validate] is empty, so the `!!`s on the
 * required fields are safe; `sodiumG` is always the derived value, never a separately-entered
 * one. `createdAtMillis`/`updatedAtMillis` are re-derived by `IngredientRepository.upsert` for a
 * new row (`id == 0L`) — `updatedAtMillis` is always stamped there.
 */
fun IngredientDraft.toIngredient(clock: PlatformClock): Ingredient = Ingredient(
    id = id,
    name = name.trim(),
    brand = brand.trim().ifBlank { null },
    barcode = barcode.trim().ifBlank { null },
    basis = basis,
    pieceGrams = pieceGrams,
    servingGrams = servingGrams,
    servingLabel = servingLabel.trim().ifBlank { null },
    kcal = requireNotNull(kcal) { "kcal must be validated before save" },
    proteinG = proteinG,
    carbsG = carbsG,
    sugarG = sugarG,
    fatG = fatG,
    satFatG = satFatG,
    fiberG = fiberG,
    saltG = saltG,
    sodiumG = sodiumG,
    isFavorite = isFavorite,
    source = source,
    offProductJson = offProductJson,
    lastUsedAtMillis = lastUsedAtMillis,
    useCount = useCount,
    archived = archived,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = clock.millis(),
)

/** Rebuilds the editor draft from a loaded [Ingredient] (editing an existing row). */
fun fromIngredient(ingredient: Ingredient): IngredientDraft = IngredientDraft(
    id = ingredient.id,
    name = ingredient.name,
    brand = ingredient.brand.orEmpty(),
    barcode = ingredient.barcode.orEmpty(),
    basis = ingredient.basis,
    pieceGrams = ingredient.pieceGrams,
    servingGrams = ingredient.servingGrams,
    servingLabel = ingredient.servingLabel.orEmpty(),
    kcal = ingredient.kcal,
    proteinG = ingredient.proteinG,
    carbsG = ingredient.carbsG,
    sugarG = ingredient.sugarG,
    fatG = ingredient.fatG,
    satFatG = ingredient.satFatG,
    fiberG = ingredient.fiberG,
    saltG = ingredient.saltG,
    isFavorite = ingredient.isFavorite,
    source = ingredient.source,
    offProductJson = ingredient.offProductJson,
    lastUsedAtMillis = ingredient.lastUsedAtMillis,
    useCount = ingredient.useCount,
    archived = ingredient.archived,
    createdAtMillis = ingredient.createdAtMillis,
)

/** The [NutritionFacts] view of this draft, for the live [com.myhealth.domain.engine.nutrition.NutritionValidator]
 * warnings banner (§3.6.1 step 7 / P4.3). */
fun IngredientDraft.toNutritionFacts(): NutritionFacts = NutritionFacts(
    basis = basis,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    sugarG = sugarG,
    fatG = fatG,
    satFatG = satFatG,
    fiberG = fiberG,
    saltG = saltG,
    sodiumG = sodiumG,
)

/**
 * True once any nutrient field carries a value. An untouched form is not "implausible", it is
 * simply empty, so the plausibility warnings stay hidden until then (verification POLISH-1).
 */
fun IngredientDraft.hasAnyNutrient(): Boolean = listOf(
    kcal, proteinG, carbsG, sugarG, fatG, satFatG, fiberG, saltG,
).any { it != null }

/** A fresh draft for a new ingredient, optionally prefilled with a scanned [barcode] (P4.3's
 * barcode-prefill hook; the OFF lookup itself arrives in P4.10). */
fun newIngredientDraft(barcode: String? = null): IngredientDraft = IngredientDraft(barcode = barcode.orEmpty())

/**
 * Prefills this draft from a scan — an OCR review the user accepted or an Open Food Facts product
 * (P4.9/P4.10). A value the scan does not carry never overwrites what is already in the form, so
 * the same function serves both "new ingredient from a scan" and "look this barcode up while
 * editing". Per-serving values arrive as `PER_PIECE`, where the serving weight *is* the piece
 * weight (§2.2.5).
 */
fun IngredientDraft.withScan(scan: ScanDraft): IngredientDraft {
    val facts = scan.facts
    return copy(
        name = scan.name.trim().ifBlank { name },
        brand = scan.brand.trim().ifBlank { brand },
        barcode = scan.barcode.trim().ifBlank { barcode },
        basis = facts.basis,
        pieceGrams = if (facts.basis == MeasureBasis.PER_PIECE) {
            facts.servingGrams ?: pieceGrams
        } else {
            pieceGrams
        },
        servingGrams = facts.servingGrams ?: servingGrams,
        servingLabel = facts.servingLabel?.takeIf { it.isNotBlank() } ?: servingLabel,
        kcal = facts.energyKcal.value ?: kcal,
        proteinG = facts.proteinG.value ?: proteinG,
        carbsG = facts.carbsG.value ?: carbsG,
        sugarG = facts.sugarG.value ?: sugarG,
        fatG = facts.fatG.value ?: fatG,
        satFatG = facts.satFatG.value ?: satFatG,
        fiberG = facts.fiberG.value ?: fiberG,
        saltG = facts.saltG.value ?: saltG,
        source = scan.source,
    )
}
