package com.myhealth.data.off

import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFactsDraft
import com.myhealth.domain.model.ParsedValue
import kotlin.math.floor

/**
 * An Open Food Facts product reduced to what the ingredient editor needs (P4.10). [facts] is the
 * same [NutritionFactsDraft] the label parser produces, so both scan paths hand the editor an
 * identical shape.
 */
data class OffProduct(
    val barcode: String,
    val name: String?,
    val brand: String?,
    val imageUrl: String?,
    val facts: NutritionFactsDraft,
)

/**
 * Maps an OFF v2 response onto [OffProduct] (PLAN P4.10). Pure and offline-testable — the
 * fixtures under `fixtures/off/` drive `OffMapperTest`.
 *
 * The nutriment keys are exactly the ten §5/P4.10 lists; values are per 100 g / 100 ml, which is
 * why the basis is `PER_100G` unless [OffProductDto.quantity] or [OffProductDto.servingSize]
 * mentions millilitres. Energy is taken from `energy-kcal_100g`; when only `energy-kj_100g` is
 * present, kcal is derived with the §3.6.1 factor (4.184) and amendment A4's half-up rounding,
 * and marked with a slightly lower confidence than a value OFF states outright.
 */
object OffMapper {

    private const val KJ_PER_KCAL = 4.184
    private const val STATED_CONFIDENCE = 1.0
    private const val DERIVED_CONFIDENCE = 0.85
    private const val SODIUM_TO_SALT = 2.5

    private val ML_HINT = Regex("""\bm\s?l\b|\bcl\b|\bml\b""", RegexOption.IGNORE_CASE)
    private val SERVING_AMOUNT = Regex("""(\d+(?:[.,]\d+)?)\s*(g|ml|gr|gramm|grams?)\b""", RegexOption.IGNORE_CASE)

    /** `null` when the response is a "not found" body (`status != 1`) or carries no product. */
    fun toProduct(response: OffResponse, barcode: String): OffProduct? {
        if (response.status != 1) return null
        val product = response.product ?: return null
        return OffProduct(
            barcode = response.code?.ifBlank { null } ?: barcode,
            name = product.productName?.trim()?.ifBlank { null },
            brand = product.brands?.split(',')?.firstOrNull()?.trim()?.ifBlank { null },
            imageUrl = product.imageUrl?.ifBlank { null },
            facts = toDraft(product),
        )
    }

    /** The nutriment half of [toProduct], exposed for tests that only care about the numbers. */
    fun toDraft(product: OffProductDto): NutritionFactsDraft {
        val n = product.nutriments
        val kcal = n.numberOf("energy-kcal_100g")
        val kj = n.numberOf("energy-kj_100g")
        val salt = n.numberOf("salt_100g")
        val sodium = n.numberOf("sodium_100g")
        return NutritionFactsDraft(
            basis = basisOf(product),
            energyKcal = when {
                kcal != null -> stated(kcal)
                kj != null -> ParsedValue(halfUp(kj / KJ_PER_KCAL), DERIVED_CONFIDENCE)
                else -> ParsedValue()
            },
            energyKj = stated(kj),
            proteinG = stated(n.numberOf("proteins_100g")),
            carbsG = stated(n.numberOf("carbohydrates_100g")),
            sugarG = stated(n.numberOf("sugars_100g")),
            fatG = stated(n.numberOf("fat_100g")),
            satFatG = stated(n.numberOf("saturated-fat_100g")),
            fiberG = stated(n.numberOf("fiber_100g")),
            saltG = when {
                salt != null -> stated(salt)
                sodium != null -> ParsedValue(sodium * SODIUM_TO_SALT, DERIVED_CONFIDENCE)
                else -> ParsedValue()
            },
            sodiumG = when {
                sodium != null -> stated(sodium)
                salt != null -> ParsedValue(salt / SODIUM_TO_SALT, DERIVED_CONFIDENCE)
                else -> ParsedValue()
            },
            servingGrams = servingAmountOf(product.servingSize),
            servingLabel = product.servingSize?.trim()?.ifBlank { null },
        )
    }

    /** `PER_100ML` when the pack size or the serving is stated in millilitres, else `PER_100G`. */
    private fun basisOf(product: OffProductDto): MeasureBasis {
        val mentionsMl = listOfNotNull(product.quantity, product.servingSize)
            .any { ML_HINT.containsMatchIn(it) }
        return if (mentionsMl) MeasureBasis.PER_100ML else MeasureBasis.PER_100G
    }

    /** `"30 g"` → `30.0`, `"250ml"` → `250.0`, `"1 slice"` → `null`. */
    fun servingAmountOf(servingSize: String?): Double? {
        val text = servingSize?.trim()?.ifBlank { null } ?: return null
        val match = SERVING_AMOUNT.find(text) ?: return null
        return match.groupValues[1].replace(',', '.').toDoubleOrNull()
    }

    private fun stated(value: Double?): ParsedValue =
        if (value == null) ParsedValue() else ParsedValue(value, STATED_CONFIDENCE)

    /** Amendment A4: half-up, not half-to-even. */
    private fun halfUp(value: Double): Double = floor(value + 0.5)
}
