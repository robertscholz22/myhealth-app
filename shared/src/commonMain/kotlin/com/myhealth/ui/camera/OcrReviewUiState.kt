package com.myhealth.ui.camera

import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFacts
import com.myhealth.domain.model.NutritionFactsDraft
import com.myhealth.domain.model.ParsedValue
import com.myhealth.domain.util.EngineWarning
import com.myhealth.resources.*
import com.myhealth.ui.common.ConfidenceLevel
import com.myhealth.ui.common.confidenceLevelOf
import com.myhealth.ui.common.fmtDecimal
import org.jetbrains.compose.resources.StringResource

/** The nine editable rows of the review form, in the order a label prints them (§3.6). */
enum class OcrField(val labelRes: StringResource, val suffixRes: StringResource, val decimals: Int) {
    KCAL(Res.string.ocr_field_calories, Res.string.ocr_unit_kcal, 0),
    FAT(Res.string.ocr_field_fat, Res.string.ocr_unit_grams, 1),
    SAT_FAT(Res.string.ocr_field_saturates, Res.string.ocr_unit_grams, 1),
    CARBS(Res.string.ocr_field_carbs, Res.string.ocr_unit_grams, 1),
    SUGAR(Res.string.ocr_field_sugars, Res.string.ocr_unit_grams, 1),
    FIBER(Res.string.ocr_field_fiber, Res.string.ocr_unit_grams, 1),
    PROTEIN(Res.string.ocr_field_protein, Res.string.ocr_unit_grams, 1),
    SALT(Res.string.ocr_field_salt, Res.string.ocr_unit_grams, 2),
    KJ(Res.string.ocr_field_energy, Res.string.ocr_unit_kj, 0),
}

/** One row: what the parser read, how sure it was, and whether it was a `< x` upper bound. */
data class OcrFieldValue(
    val field: OcrField,
    val value: Double? = null,
    val confidence: Double = 0.0,
    val isUpperBound: Boolean = false,
) {
    val level: ConfidenceLevel get() = confidenceLevelOf(confidence)
}

/**
 * State of [OcrReviewScreen] (PLAN §4.2 "OCR review", P4.9): the captured picture, the parsed
 * draft as editable rows with their confidence, the parser's warnings, and the per-serving toggle.
 *
 * Nothing in here is ever saved on its own — "Accept" only hands the confirmed numbers to the
 * ingredient editor (brief §2.1, risk R9).
 */
data class OcrReviewUiState(
    val draft: NutritionFactsDraft = NutritionFactsDraft(basis = MeasureBasis.PER_100G),
    val values: List<OcrFieldValue> = emptyList(),
    val imagePath: String? = null,
    val name: String = "",
    val brand: String = "",
    val barcode: String = "",
    val source: String = ScanDraft.SOURCE_OCR,
    val usePerServing: Boolean = false,
    val warnings: List<EngineWarning> = emptyList(),
    /** No scan is in the [DraftStore] (e.g. the screen was reopened from the back stack). */
    val missing: Boolean = false,
) {
    /** The toggle only appears when the parser actually found a second, per-serving column. */
    val hasPerServing: Boolean get() = draft.perServing != null

    /** The line under the form that says what the numbers refer to. */
    val basisNote: String
        get() = if (usePerServing) {
            val grams = draft.servingGrams
            val label = draft.servingLabel?.takeIf { it.isNotBlank() }
            when {
                label != null -> "Values are per serving ($label)"
                grams != null -> "Values are per serving (${grams.trimZeros()} g)"
                else -> "Values are per serving"
            }
        } else {
            when (draft.basis) {
                MeasureBasis.PER_100G -> "Values are per 100 g"
                MeasureBasis.PER_100ML -> "Values are per 100 ml"
                MeasureBasis.PER_PIECE -> "Values are per piece"
            }
        }

    /** The first red row — the review screen focuses it so the worst read is fixed first (P4.9). */
    val firstLowConfidence: OcrField?
        get() = values.firstOrNull { it.value != null && it.level == ConfidenceLevel.LOW }?.field

    fun valueOf(field: OcrField): OcrFieldValue =
        values.firstOrNull { it.field == field } ?: OcrFieldValue(field)
}

/** Builds the review state from what the scan screen left in the [DraftStore]. */
fun reviewStateOf(scan: ScanDraft?): OcrReviewUiState {
    if (scan == null) return OcrReviewUiState(missing = true)
    return OcrReviewUiState(
        draft = scan.facts,
        values = fieldValuesOf(scan.facts, usePerServing = false),
        imagePath = scan.imagePath,
        name = scan.name,
        brand = scan.brand,
        barcode = scan.barcode,
        source = scan.source,
        warnings = scan.warnings.ifEmpty { scan.facts.warnings },
    )
}

/**
 * The rows for one column of the draft. The per-serving column carries no confidence of its own
 * (the parser stores it as plain [NutritionFacts]), so each per-serving row inherits the
 * confidence of the per-100 row it was read beside — same line, same reliability (§3.6.1 step 4).
 */
fun fieldValuesOf(draft: NutritionFactsDraft, usePerServing: Boolean): List<OcrFieldValue> {
    val serving = draft.perServing.takeIf { usePerServing }
    return OcrField.entries.map { field ->
        val parsed = draft.parsedOf(field)
        if (serving == null) {
            OcrFieldValue(field, parsed.value, parsed.confidence, parsed.isUpperBound)
        } else {
            OcrFieldValue(field, serving.valueOf(field), parsed.confidence, parsed.isUpperBound)
        }
    }
}

/** Swaps which column the form shows; the values and [OcrReviewUiState.basisNote] follow. */
fun OcrReviewUiState.withPerServing(use: Boolean): OcrReviewUiState {
    if (!hasPerServing && use) return this
    return copy(usePerServing = use, values = fieldValuesOf(draft, use))
}

/** A user edit: the typed number replaces the parsed one and is trusted completely. */
fun OcrReviewUiState.withValue(field: OcrField, value: Double?): OcrReviewUiState = copy(
    values = values.map { row ->
        if (row.field == field) row.copy(value = value, confidence = 1.0, isUpperBound = false) else row
    },
)

/**
 * The facts "Accept" hands on: the edited numbers, and — when the per-serving column is in use —
 * `PER_PIECE` with the serving weight, because that is what those numbers are per (§2.2.5).
 */
fun OcrReviewUiState.acceptedFacts(): NutritionFactsDraft = draft.copy(
    basis = if (usePerServing) MeasureBasis.PER_PIECE else draft.basis,
    energyKcal = valueOf(OcrField.KCAL).toParsed(),
    energyKj = valueOf(OcrField.KJ).toParsed(),
    fatG = valueOf(OcrField.FAT).toParsed(),
    satFatG = valueOf(OcrField.SAT_FAT).toParsed(),
    carbsG = valueOf(OcrField.CARBS).toParsed(),
    sugarG = valueOf(OcrField.SUGAR).toParsed(),
    fiberG = valueOf(OcrField.FIBER).toParsed(),
    proteinG = valueOf(OcrField.PROTEIN).toParsed(),
    saltG = valueOf(OcrField.SALT).toParsed(),
    sodiumG = valueOf(OcrField.SALT).value?.let { salt ->
        ParsedValue(salt / 2.5, valueOf(OcrField.SALT).confidence)
    } ?: ParsedValue(),
    perServing = null,
)

private fun OcrFieldValue.toParsed(): ParsedValue =
    ParsedValue(value = value, confidence = confidence, isUpperBound = isUpperBound)

private fun NutritionFactsDraft.parsedOf(field: OcrField): ParsedValue = when (field) {
    OcrField.KCAL -> energyKcal
    OcrField.KJ -> energyKj
    OcrField.FAT -> fatG
    OcrField.SAT_FAT -> satFatG
    OcrField.CARBS -> carbsG
    OcrField.SUGAR -> sugarG
    OcrField.FIBER -> fiberG
    OcrField.PROTEIN -> proteinG
    OcrField.SALT -> saltG
}

private fun NutritionFacts.valueOf(field: OcrField): Double? = when (field) {
    OcrField.KCAL -> kcal
    OcrField.KJ -> null
    OcrField.FAT -> fatG
    OcrField.SAT_FAT -> satFatG
    OcrField.CARBS -> carbsG
    OcrField.SUGAR -> sugarG
    OcrField.FIBER -> fiberG
    OcrField.PROTEIN -> proteinG
    OcrField.SALT -> saltG
}

private fun Double.trimZeros(): String =
    if (this == toLong().toDouble()) toLong().toString() else fmtDecimal(this, 1)
