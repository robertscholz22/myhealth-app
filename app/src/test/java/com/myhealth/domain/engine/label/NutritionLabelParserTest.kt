package com.myhealth.domain.engine.label

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFactsDraft
import org.junit.Test

/**
 * The 17 named cases of PLAN §3.6.3 over the ten fixtures of §3.6.2, plus the two-column English
 * label and the §6.6 parse-time budget.
 */
class NutritionLabelParserTest {

    @Test
    fun ocr01_german_basic_per_100g() {
        val draft = draftOf("de_haferflocken_100g")

        assertThat(draft.basis).isEqualTo(MeasureBasis.PER_100G)
        assertThat(draft.energyKcal.value).isEqualTo(370.0)
        assertThat(draft.proteinG.value).isEqualTo(13.5)
        assertThat(draft.carbsG.value).isEqualTo(58.7)
        assertThat(draft.fatG.value).isEqualTo(7.0)
        assertThat(draft.fiberG.value).isEqualTo(10.0)
        assertThat(draft.saltG.value).isEqualTo(0.02)
        assertThat(draft.energyKcal.confidence).isEqualTo(1.0)
    }

    @Test
    fun ocr02_german_comma_decimals_parsed() {
        val draft = draftOf("de_haferflocken_100g")

        assertThat(draft.proteinG.value).isEqualTo(13.5)
        assertThat(draft.satFatG.value).isEqualTo(1.3)
        assertThat(draft.sugarG.value).isEqualTo(1.1)
    }

    @Test
    fun ocr03_kj_only_converts_to_kcal() {
        val draft = draftOf("de_kj_only")

        assertThat(draft.energyKj.value).isEqualTo(1560.0)
        assertThat(draft.energyKcal.value).isEqualTo(373.0)
        assertThat(draft.energyKcal.confidence).isWithin(1e-9).of(0.85)
    }

    @Test
    fun ocr04_of_which_lines_matched_before_parent() {
        val draft = draftOf("de_haferflocken_100g")

        // `davon Zucker 1,1 g` never lands in the carbohydrate slot and never steals its line.
        assertThat(draft.carbsG.value).isEqualTo(58.7)
        assertThat(draft.sugarG.value).isEqualTo(1.1)
        assertThat(draft.carbsG.sourceLineIndex).isEqualTo(5)
        assertThat(draft.sugarG.sourceLineIndex).isEqualTo(6)
        // The parent keyword may not capture a `davon` line even when it is merged into it.
        val merged = draftOf("de_umlaut_ocr_noise")
        assertThat(merged.fatG.value).isEqualTo(7.0)
        assertThat(merged.satFatG.value).isEqualTo(1.3)
    }

    @Test
    fun ocr05_saturated_fat_german_long_form() {
        val lines = listOf(
            OcrLine("Nährwerte pro 100 g", 40, 110, 420, 150),
            OcrLine("Fett 9,0 g", 40, 160, 280, 200),
            OcrLine("davon gesättigte Fettsäuren 1,2 g", 40, 210, 700, 250),
        )

        val draft = draftOf(lines)

        assertThat(draft.satFatG.value).isEqualTo(1.2)
        assertThat(draft.satFatG.confidence).isEqualTo(1.0)
        assertThat(draft.fatG.value).isEqualTo(9.0)
    }

    @Test
    fun ocr06_two_columns_prefers_per_100g() {
        val draft = draftOf("de_skyr_two_columns")

        assertThat(draft.energyKcal.value).isEqualTo(63.0)
        assertThat(draft.energyKj.value).isEqualTo(265.0)
        assertThat(draft.proteinG.value).isEqualTo(11.0)
        assertThat(draft.carbsG.value).isEqualTo(4.0)
        assertThat(draft.fatG.value).isEqualTo(0.2)
        assertThat(draft.satFatG.value).isEqualTo(0.1)
        assertThat(draft.saltG.value).isEqualTo(0.13)
        assertThat(draft.servingGrams).isEqualTo(150.0)
        assertThat(draft.servingLabel).isEqualTo("150 g")
        val serving = requireNotNull(draft.perServing)
        assertThat(serving.kcal).isEqualTo(95.0)
        assertThat(serving.proteinG).isEqualTo(16.5)
        assertThat(serving.fatG).isEqualTo(0.3)
        assertThat(serving.saltG).isEqualTo(0.2)
        // The long `davon gesättigte Fettsäuren` row needs the left-to-right repair -> x0.9.
        assertThat(draft.satFatG.confidence).isWithin(1e-9).of(0.9)
    }

    @Test
    fun ocr07_english_uk_labels() {
        val draft = draftOf("en_uk_oats")

        assertThat(draft.energyKcal.value).isEqualTo(372.0)
        assertThat(draft.satFatG.value).isEqualTo(1.4)
        assertThat(draft.fiberG.value).isEqualTo(9.0)
        assertThat(draft.sugarG.value).isEqualTo(1.0)
        assertThat(draft.proteinG.value).isEqualTo(11.0)
        assertThat(draft.saltG.value).isEqualTo(0.01)
    }

    @Test
    fun ocr08_english_us_serving_only_sets_basis() {
        val result = NutritionLabelParser.parse(loadOcrFixture("en_us_serving_only"))
        val success = result as LabelParseResult.Success

        assertThat(success.warnings.map { it.code }).contains(EngineWarningCode.COLUMN_AMBIGUOUS)
        assertThat(success.draft.servingGrams).isEqualTo(40.0)
        assertThat(success.draft.servingLabel).isEqualTo("40 g")
        val serving = requireNotNull(success.draft.perServing)
        assertThat(serving.kcal).isEqualTo(190.0)
        assertThat(serving.fatG).isEqualTo(9.0)
        assertThat(serving.satFatG).isEqualTo(1.5)
        assertThat(serving.carbsG).isEqualTo(22.0)
        assertThat(serving.fiberG).isEqualTo(3.0)
        assertThat(serving.sugarG).isEqualTo(12.0)
        assertThat(serving.proteinG).isEqualTo(4.0)
        // Sodium 160 mg -> 0.16 g, and the serving column costs the x0.9 ambiguity factor.
        assertThat(serving.sodiumG).isWithin(1e-9).of(0.16)
        assertThat(success.draft.proteinG.confidence).isWithin(1e-9).of(0.9)
    }

    @Test
    fun ocr09_lower_bound_value() {
        val draft = draftOf("de_lower_bound")

        assertThat(draft.sugarG.value).isEqualTo(0.5)
        assertThat(draft.sugarG.isUpperBound).isTrue()
        assertThat(draft.satFatG.value).isEqualTo(0.1)
        assertThat(draft.satFatG.isUpperBound).isTrue()
        assertThat(draft.carbsG.isUpperBound).isFalse()
    }

    @Test
    fun ocr10_sodium_converted_to_salt() {
        val lines = listOf(
            OcrLine("Nährwerte pro 100 g", 40, 110, 420, 150),
            OcrLine("Energie 1000 kJ / 239 kcal", 40, 160, 600, 200),
            OcrLine("Fett 10,0 g", 40, 210, 300, 250),
            OcrLine("Kohlenhydrate 30,0 g", 40, 260, 460, 300),
            OcrLine("Eiweiß 5,0 g", 40, 310, 340, 350),
            OcrLine("Natrium 0,2 g", 40, 360, 370, 400),
        )

        val draft = draftOf(lines)

        assertThat(draft.sodiumG.value).isEqualTo(0.2)
        assertThat(draft.saltG.value).isWithin(1e-9).of(0.5)
        assertThat(draft.saltG.confidence).isWithin(1e-9).of(0.85)
    }

    @Test
    fun ocr11_per_100ml_detected_for_beverage() {
        val draft = draftOf("de_milch_100ml")

        assertThat(draft.basis).isEqualTo(MeasureBasis.PER_100ML)
        assertThat(draft.energyKcal.value).isEqualTo(65.0)
        assertThat(draft.fatG.value).isEqualTo(3.5)
        assertThat(draft.proteinG.value).isEqualTo(3.4)
    }

    @Test
    fun ocr12_energy_mismatch_warning() {
        val doctored = loadOcrFixture("de_haferflocken_100g").replacing("Fett 7,0 g", "Fett 70,0 g")

        val success = NutritionLabelParser.parse(doctored) as LabelParseResult.Success

        assertThat(success.draft.fatG.value).isEqualTo(70.0)
        assertThat(success.warnings.map { it.code }).contains(EngineWarningCode.ENERGY_MISMATCH)
    }

    @Test
    fun ocr13_sugar_greater_than_carbs_warning() {
        val doctored = loadOcrFixture("de_haferflocken_100g")
            .replacing("davon Zucker 1,1 g", "davon Zucker 61,1 g")

        val success = NutritionLabelParser.parse(doctored) as LabelParseResult.Success

        assertThat(success.draft.sugarG.value).isEqualTo(61.1)
        assertThat(success.warnings.map { it.code }).contains(EngineWarningCode.IMPLAUSIBLE_VALUE)
    }

    @Test
    fun ocr14_umlaut_and_noise_tolerated() {
        val draft = draftOf("de_umlaut_ocr_noise")

        assertThat(LabelValidator.recognisedFieldCount(draft)).isAtLeast(4)
        assertThat(draft.energyKcal.value).isEqualTo(370.0)
        assertThat(draft.carbsG.value).isEqualTo(58.7)
        assertThat(draft.fiberG.value).isEqualTo(10.0)
        assertThat(draft.proteinG.value).isEqualTo(13.5)
        assertThat(draft.saltG.value).isEqualTo(0.02)
        // `Kohlenhydrafe` needed the Levenshtein <= 1 fuzz, so its confidence is 1.0 x 0.8.
        assertThat(draft.carbsG.confidence).isWithin(1e-9).of(0.8)
    }

    @Test
    fun ocr15_garbage_returns_failed() {
        val result = NutritionLabelParser.parse(loadOcrFixture("garbage_no_nutrients"))

        assertThat(result).isInstanceOf(LabelParseResult.Failed::class.java)
        assertThat((result as LabelParseResult.Failed).reason)
            .isEqualTo(EngineWarningCode.NO_NUTRIENTS_FOUND)
    }

    @Test
    fun ocr16_thousands_separator_in_kj() {
        val draft = draftOf("de_kj_only")

        assertThat(draft.energyKj.value).isEqualTo(1560.0)
        assertThat(draft.fatG.value).isEqualTo(20.0)
        assertThat(draft.carbsG.value).isEqualTo(30.0)
        assertThat(draft.proteinG.value).isEqualTo(12.0)
    }

    @Test
    fun ocr17_confidence_lower_for_next_line_number() {
        val draft = draftOf("de_lower_bound")

        assertThat(draft.fiberG.value).isEqualTo(2.5)
        assertThat(draft.fiberG.confidence).isWithin(1e-9).of(0.75)
        assertThat(draft.fiberG.sourceLineIndex).isEqualTo(8)
        assertThat(draft.proteinG.confidence).isEqualTo(1.0)
    }

    @Test
    fun ocr18_english_two_columns_populates_both() {
        val draft = draftOf("en_two_columns")

        assertThat(draft.energyKcal.value).isEqualTo(447.0)
        assertThat(draft.energyKj.value).isEqualTo(1870.0)
        assertThat(draft.fatG.value).isEqualTo(18.0)
        assertThat(draft.carbsG.value).isEqualTo(60.0)
        assertThat(draft.fiberG.value).isEqualTo(7.0)
        assertThat(draft.servingGrams).isEqualTo(45.0)
        val serving = requireNotNull(draft.perServing)
        assertThat(serving.kcal).isEqualTo(201.0)
        assertThat(serving.fatG).isEqualTo(8.1)
        assertThat(serving.carbsG).isEqualTo(27.0)
        assertThat(serving.proteinG).isEqualTo(4.5)
    }

    @Test
    fun ocr19_forty_lines_parse_well_under_the_budget() {
        val fixture = loadOcrFixture("de_haferflocken_100g")
        val lines = (0 until 4).flatMap { block ->
            fixture.map { line -> line.copy(top = line.top + block * 600) }
        }
        assertThat(lines).hasSize(40)
        repeat(20) { NutritionLabelParser.parse(lines) } // warm-up, as in the §6.6 budget

        val startNanos = System.nanoTime()
        val result = NutritionLabelParser.parse(lines)
        val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000.0

        assertThat(result).isInstanceOf(LabelParseResult.Success::class.java)
        assertThat(elapsedMillis).isLessThan(200.0)
    }

    private fun draftOf(fixture: String): NutritionFactsDraft = draftOf(loadOcrFixture(fixture))

    private fun draftOf(lines: List<OcrLine>): NutritionFactsDraft =
        (NutritionLabelParser.parse(lines) as LabelParseResult.Success).draft

    private fun List<OcrLine>.replacing(original: String, replacement: String): List<OcrLine> =
        map { if (it.text == original) it.copy(text = replacement) else it }
}

/**
 * Loads `app/src/test/resources/fixtures/ocr/<name>.txt` (§3.6.2) off the classpath, so rule R12
 * (`user.dir` is the module directory) cannot bite. One line per OCR line:
 * `text|left|top|right|bottom`.
 */
fun loadOcrFixture(name: String): List<OcrLine> {
    val path = "fixtures/ocr/$name.txt"
    val url = checkNotNull(NutritionLabelParser::class.java.classLoader).getResource(path)
        ?: error("Missing OCR fixture on the classpath: $path")
    return url.readText().lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split('|')
            require(parts.size == 5) { "Bad fixture line in $path: $line" }
            OcrLine(
                text = parts[0],
                left = parts[1].trim().toInt(),
                top = parts[2].trim().toInt(),
                right = parts[3].trim().toInt(),
                bottom = parts[4].trim().toInt(),
            )
        }
        .toList()
}
