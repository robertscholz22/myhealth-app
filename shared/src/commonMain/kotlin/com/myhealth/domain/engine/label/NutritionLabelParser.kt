package com.myhealth.domain.engine.label

import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFacts
import com.myhealth.domain.model.NutritionFactsDraft
import com.myhealth.domain.model.ParsedValue
import com.myhealth.domain.util.EngineWarning
import kotlin.math.abs
import kotlin.math.floor

/**
 * The nutrition label parser of PLAN §3.6: normalise, tokenise numbers, match nutrient keywords
 * longest-first, resolve the per-100 / per-portion columns by header `centerX`, normalise units,
 * derive what can be derived, validate, and score every field's confidence.
 *
 * Pure Kotlin: the camera and ML Kit side (P4.8) only has to produce [OcrLine]s.
 */
object NutritionLabelParser {

    private const val KJ_PER_KCAL = 4.184
    private const val SODIUM_TO_SALT = 2.5
    private const val NEXT_LINE_CONFIDENCE = 0.75
    private const val FUZZY_FACTOR = 0.8
    private const val AMBIGUOUS_FACTOR = 0.9
    private const val DERIVED_FACTOR = 0.85

    private val PER_100_HEADER = Regex("""\b100\s*(g|ml)\b""")
    private val SERVING_HEADER = Regex("""\b(pro|je|per)\s*(portion|stuck|serving)\b""")
    private val SERVING_SIZE_LINE = Regex("""serving\s*size|portionsgrosse|portionsgroesse""")
    private val ML_HINT = Regex("""\b100\s*ml\b|getrank|beverage|drink""")

    private enum class Col { PER_100, SERVING }

    /** A nutrient slot of the draft — [NutrientField] with energy split by unit. */
    private enum class Slot { KJ, KCAL, FAT, SAT_FAT, CARBS, SUGAR, FIBER, PROTEIN, SALT, SODIUM }

    private data class Header(val col: Col, val centerX: Int)

    private data class Layout(
        val headers: List<Header>,
        val headerLines: Set<Int>,
        val basis: MeasureBasis,
        val servingGrams: Double?,
        val servingLabel: String?,
    ) {
        val servingOnly: Boolean get() = headers.isNotEmpty() && headers.none { it.col == Col.PER_100 }
    }

    private data class Candidate(
        val slot: Slot,
        val value: Double,
        val isUpperBound: Boolean,
        val col: Col,
        val lineIndex: Int,
        val confidence: Double,
    )

    fun parse(lines: List<OcrLine>): LabelParseResult {
        val texts = lines.map { Lexicon.normaliseKeepingSpacing(it.text) }
        val layout = detectLayout(lines, texts)
        val candidates = mutableListOf<Candidate>()
        val multiNumberLines = mutableSetOf<Int>()
        texts.indices.forEach { index ->
            if (index !in layout.headerLines) {
                collectLine(lines, texts, index, layout, candidates, multiNumberLines)
            }
        }

        val warnings = mutableListOf<EngineWarning>()
        if (layout.servingOnly) {
            warnings += EngineWarning(
                EngineWarningCode.COLUMN_AMBIGUOUS,
                "Only a per-serving column was found; values are per serving, not per 100 g.",
            )
        } else if (layout.headers.isEmpty() && multiNumberLines.size >= 2) {
            warnings += EngineWarning(
                EngineWarningCode.COLUMN_AMBIGUOUS,
                "No per-100 header was found and several lines carry more than one number.",
            )
        }

        val mainCol = if (layout.servingOnly) Col.SERVING else Col.PER_100
        val main = derive(slotsOf(candidates, mainCol))
        val serving = derive(slotsOf(candidates, Col.SERVING))

        val draft = NutritionFactsDraft(
            basis = layout.basis,
            energyKcal = main[Slot.KCAL] ?: ParsedValue(),
            energyKj = main[Slot.KJ] ?: ParsedValue(),
            proteinG = main[Slot.PROTEIN] ?: ParsedValue(),
            carbsG = main[Slot.CARBS] ?: ParsedValue(),
            sugarG = main[Slot.SUGAR] ?: ParsedValue(),
            fatG = main[Slot.FAT] ?: ParsedValue(),
            satFatG = main[Slot.SAT_FAT] ?: ParsedValue(),
            fiberG = main[Slot.FIBER] ?: ParsedValue(),
            saltG = main[Slot.SALT] ?: ParsedValue(),
            sodiumG = main[Slot.SODIUM] ?: ParsedValue(),
            servingGrams = layout.servingGrams,
            servingLabel = layout.servingLabel,
            perServing = servingFacts(serving),
        )
        if (LabelValidator.recognisedFieldCount(draft) == 0) {
            return LabelParseResult.Failed(EngineWarningCode.NO_NUTRIENTS_FOUND)
        }
        warnings += LabelValidator.validate(draft)
        return LabelParseResult.Success(draft.copy(warnings = warnings.toList()), warnings.toList())
    }

    // ---- step 4: columns -------------------------------------------------------------------

    private fun detectLayout(lines: List<OcrLine>, texts: List<String>): Layout {
        val headers = mutableListOf<Header>()
        val headerLines = mutableSetOf<Int>()
        var servingLabel: String? = null
        var servingGrams: Double? = null

        texts.forEachIndexed { index, text ->
            val per100 = PER_100_HEADER.find(text)
            val serving = SERVING_HEADER.find(text)
            if (per100 == null && serving == null) return@forEachIndexed
            headerLines += index
            if (per100 != null && headers.none { it.col == Col.PER_100 }) {
                headers += Header(Col.PER_100, centerOf(lines[index], text, per100.range))
            }
            if (serving != null && headers.none { it.col == Col.SERVING }) {
                headers += Header(Col.SERVING, centerOf(lines[index], text, serving.range))
                NumberTokenizer.parenthesised(text)?.let {
                    servingLabel = it
                    servingGrams = NumberTokenizer.gramsIn(it)
                }
            }
        }
        if (servingGrams == null) {
            texts.firstOrNull { SERVING_SIZE_LINE.containsMatchIn(it) }
                ?.let { NumberTokenizer.parenthesised(it) }
                ?.let { servingLabel = it; servingGrams = NumberTokenizer.gramsIn(it) }
        }
        val basis = if (texts.any { ML_HINT.containsMatchIn(it) }) {
            MeasureBasis.PER_100ML
        } else {
            MeasureBasis.PER_100G
        }
        return Layout(headers, headerLines, basis, servingGrams, servingLabel)
    }

    private fun centerOf(line: OcrLine, text: String, range: IntRange): Int =
        line.xAt((range.first + range.last) / 2, text.length)

    /**
     * Assigns every number of a segment to a column. With two headers the number's estimated
     * x-position picks the nearest one; when that maps both numbers of a two-column row onto the
     * same header — which the uniform-character-width estimate does for long keywords such as
     * `davon gesattigte fettsauren` — the row is repaired left-to-right and flagged ambiguous.
     */
    private fun assign(
        tokens: List<NumberToken>,
        segment: LineSegment,
        line: OcrLine,
        headers: List<Header>,
    ): List<Pair<Col, Boolean>> {
        if (headers.size <= 1) {
            val col = headers.firstOrNull()?.col ?: Col.PER_100
            return tokens.map { col to false }
        }
        val sorted = headers.sortedBy { it.centerX }
        val nearest = tokens.map { token ->
            val x = line.xAt(segment.baseOffset + token.centerOffset, segment.lineLength)
            sorted.minBy { abs(it.centerX - x) }.col
        }
        if (tokens.size != sorted.size || nearest.distinct().size == nearest.size) {
            return nearest.map { it to false }
        }
        val out = MutableList(tokens.size) { Col.PER_100 to true }
        tokens.indices.sortedBy { tokens[it].start }
            .forEachIndexed { rank, index -> out[index] = sorted[rank].col to true }
        return out
    }

    // ---- step 3 + 2: keywords and numbers, line by line -------------------------------------

    private fun collectLine(
        lines: List<OcrLine>,
        texts: List<String>,
        index: Int,
        layout: Layout,
        into: MutableList<Candidate>,
        multiNumberLines: MutableSet<Int>,
    ) {
        Lexicon.segments(texts[index]).forEach { segment ->
            val hit = Lexicon.match(segment.text) ?: return@forEach
            var tokens = NumberTokenizer.tokenize(segment.text)
            var base = 1.0
            var sourceLine = index
            var sourceSegment = segment
            if (tokens.isEmpty()) {
                val next = nextLineNumbers(texts, index, layout)
                if (next != null) {
                    tokens = next.second
                    base = NEXT_LINE_CONFIDENCE
                    sourceLine = next.first
                    sourceSegment = LineSegment(texts[next.first], 0, texts[next.first].length)
                }
            }
            if (tokens.isEmpty()) return@forEach
            if (tokens.size > 1) multiNumberLines += sourceLine
            // With no header at all only the first number of a line is per-100 (§3.6.1 step 4);
            // an energy line still keeps both of its units.
            val used = if (layout.headers.isEmpty() && hit.field != NutrientField.ENERGY) {
                tokens.take(1)
            } else {
                tokens
            }
            val assigned = assign(used, sourceSegment, lines[sourceLine], layout.headers)
            used.forEachIndexed { i, token ->
                val (col, ambiguous) = assigned[i]
                var confidence = base
                if (hit.fuzzy) confidence *= FUZZY_FACTOR
                if (ambiguous || layout.servingOnly) confidence *= AMBIGUOUS_FACTOR
                into += Candidate(
                    slot = slotOf(hit.field, token),
                    value = grams(hit.field, token),
                    isUpperBound = token.isUpperBound,
                    col = col,
                    lineIndex = sourceLine,
                    confidence = confidence.coerceIn(0.0, 1.0),
                )
            }
        }
    }

    /** Numbers on the line *after* a keyword-only line, provided that line names no nutrient. */
    private fun nextLineNumbers(
        texts: List<String>,
        index: Int,
        layout: Layout,
    ): Pair<Int, List<NumberToken>>? {
        val next = index + 1
        val text = texts.getOrNull(next) ?: return null
        if (next in layout.headerLines) return null
        if (Lexicon.segments(text).any { Lexicon.match(it.text) != null }) return null
        val tokens = NumberTokenizer.tokenize(text)
        return if (tokens.isEmpty()) null else next to tokens
    }

    // ---- steps 5 + 6: units, upper bounds, derivations ---------------------------------------

    private fun slotOf(field: NutrientField, token: NumberToken): Slot = when (field) {
        NutrientField.ENERGY -> if (isKj(token)) Slot.KJ else Slot.KCAL
        NutrientField.FAT -> Slot.FAT
        NutrientField.SAT_FAT -> Slot.SAT_FAT
        NutrientField.CARBS -> Slot.CARBS
        NutrientField.SUGAR -> Slot.SUGAR
        NutrientField.FIBER -> Slot.FIBER
        NutrientField.PROTEIN -> Slot.PROTEIN
        NutrientField.SALT -> Slot.SALT
        NutrientField.SODIUM -> Slot.SODIUM
    }

    /** An unmarked energy number is kJ only when it is far too large to be kcal per 100 g. */
    private fun isKj(token: NumberToken): Boolean =
        token.unit == NumberUnit.KJ || (token.unit != NumberUnit.KCAL && token.value >= 1000.0)

    private fun grams(field: NutrientField, token: NumberToken): Double = when {
        field == NutrientField.ENERGY -> token.value
        token.unit == NumberUnit.MG -> token.value / 1_000.0
        token.unit == NumberUnit.UG -> token.value / 1_000_000.0
        else -> token.value
    }

    private fun slotsOf(candidates: List<Candidate>, col: Col): MutableMap<Slot, ParsedValue> {
        val out = mutableMapOf<Slot, ParsedValue>()
        Slot.entries.forEach { slot ->
            val best = candidates.filter { it.col == col && it.slot == slot }
                .maxByOrNull { it.confidence } ?: return@forEach
            out[slot] = ParsedValue(best.value, best.confidence, best.lineIndex, best.isUpperBound)
        }
        return out
    }

    private fun derive(slots: MutableMap<Slot, ParsedValue>): Map<Slot, ParsedValue> {
        val kj = slots[Slot.KJ]
        if (slots[Slot.KCAL] == null && kj?.value != null) {
            slots[Slot.KCAL] = ParsedValue(
                value = halfUp(kj.value / KJ_PER_KCAL),
                confidence = (kj.confidence * DERIVED_FACTOR).coerceIn(0.0, 1.0),
                sourceLineIndex = kj.sourceLineIndex,
                isUpperBound = kj.isUpperBound,
            )
        }
        val salt = slots[Slot.SALT]
        val sodium = slots[Slot.SODIUM]
        if (salt?.value == null && sodium?.value != null) {
            slots[Slot.SALT] = scaled(sodium, SODIUM_TO_SALT)
        } else if (sodium?.value == null && salt?.value != null) {
            slots[Slot.SODIUM] = scaled(salt, 1.0 / SODIUM_TO_SALT)
        }
        return slots
    }

    private fun scaled(from: ParsedValue, factor: Double): ParsedValue = ParsedValue(
        value = from.value!! * factor,
        confidence = (from.confidence * DERIVED_FACTOR).coerceIn(0.0, 1.0),
        sourceLineIndex = from.sourceLineIndex,
        isUpperBound = from.isUpperBound,
    )

    private fun servingFacts(slots: Map<Slot, ParsedValue>): NutritionFacts? {
        if (slots.values.none { it.value != null }) return null
        return NutritionFacts(
            basis = MeasureBasis.PER_PIECE,
            kcal = slots[Slot.KCAL]?.value,
            proteinG = slots[Slot.PROTEIN]?.value,
            carbsG = slots[Slot.CARBS]?.value,
            sugarG = slots[Slot.SUGAR]?.value,
            fatG = slots[Slot.FAT]?.value,
            satFatG = slots[Slot.SAT_FAT]?.value,
            fiberG = slots[Slot.FIBER]?.value,
            saltG = slots[Slot.SALT]?.value,
            sodiumG = slots[Slot.SODIUM]?.value,
        )
    }

    /** Amendment A4: half-up, not half-to-even. */
    private fun halfUp(value: Double): Double = floor(value + 0.5)
}
