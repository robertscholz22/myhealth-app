package com.myhealth.domain.engine.label

import kotlin.math.abs
import kotlin.math.min

/** The nutrient slots a label line can name (§3.6.1 step 3). */
enum class NutrientField { ENERGY, FAT, SAT_FAT, CARBS, SUGAR, FIBER, PROTEIN, SALT, SODIUM }

/** One keyword hit on a line segment: which [field] it names and whether it needed the fuzz. */
data class KeywordHit(val field: NutrientField, val keyword: String, val fuzzy: Boolean)

/** A line split at `davon` / `of which`; [baseOffset] is the segment's start in the parent line. */
data class LineSegment(val text: String, val baseOffset: Int, val lineLength: Int)

/**
 * Text normalisation (§3.6.1 step 1) and the nutrient keyword table (step 3), with
 * longest-keyword-first matching and a Levenshtein ≤ 1 fuzz for keywords of ≥ 6 characters.
 */
object Lexicon {

    private val WHITESPACE = Regex("""\s+""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val SEPARATORS = Regex("""[·•|]""")
    private val DASHES = Regex("[\\u2010-\\u2015\\u2212]")

    /** `davon …` / `of which …` markers: such a segment may only ever name a child nutrient. */
    private val CHILD_MARKER = Regex("""\bdavon\b|\bof which\b""")
    private val CHILD_FIELDS = setOf(NutrientField.SAT_FAT, NutrientField.SUGAR)

    /** The §3.6.1 keyword table, already in normalised spelling (`eiweiß` folds onto `eiweiss`). */
    private val KEYWORDS: List<Pair<String, NutrientField>> = listOf(
        "brennwert" to NutrientField.ENERGY,
        "energiewert" to NutrientField.ENERGY,
        "energie" to NutrientField.ENERGY,
        "kalorien" to NutrientField.ENERGY,
        "kcal" to NutrientField.ENERGY,
        "energy" to NutrientField.ENERGY,
        "calories" to NutrientField.ENERGY,
        "fett" to NutrientField.FAT,
        "total fat" to NutrientField.FAT,
        "fat" to NutrientField.FAT,
        "davon gesattigte fettsauren" to NutrientField.SAT_FAT,
        "gesattigte fettsauren" to NutrientField.SAT_FAT,
        "davon gesattigte" to NutrientField.SAT_FAT,
        "gesattigte" to NutrientField.SAT_FAT,
        "of which saturated fatty acids" to NutrientField.SAT_FAT,
        "of which saturates" to NutrientField.SAT_FAT,
        "saturated fat" to NutrientField.SAT_FAT,
        "saturates" to NutrientField.SAT_FAT,
        "kohlenhydrate" to NutrientField.CARBS,
        "total carbohydrate" to NutrientField.CARBS,
        "carbohydrates" to NutrientField.CARBS,
        "carbohydrate" to NutrientField.CARBS,
        "davon zucker" to NutrientField.SUGAR,
        "zucker" to NutrientField.SUGAR,
        "of which sugars" to NutrientField.SUGAR,
        "total sugars" to NutrientField.SUGAR,
        "sugars" to NutrientField.SUGAR,
        "sugar" to NutrientField.SUGAR,
        "ballaststoffe" to NutrientField.FIBER,
        "dietary fibre" to NutrientField.FIBER,
        "dietary fiber" to NutrientField.FIBER,
        "fibre" to NutrientField.FIBER,
        "fiber" to NutrientField.FIBER,
        "eiweiss" to NutrientField.PROTEIN,
        "protein" to NutrientField.PROTEIN,
        "salz" to NutrientField.SALT,
        "salt" to NutrientField.SALT,
        "natrium" to NutrientField.SODIUM,
        "sodium" to NutrientField.SODIUM,
    ).sortedByDescending { it.first.length }

    /**
     * Scanner spellings that the Levenshtein ≤ 1 fuzz cannot reach but which every German label
     * scan produces: `Eiweiß` read as `EiweiB` normalises to `eiweib`, two edits away from
     * `eiweiss`. Treated as a fuzzy hit, so it carries the ×0.8 confidence penalty.
     */
    private val OCR_VARIANTS: List<Pair<String, NutrientField>> = listOf(
        "eiweib" to NutrientField.PROTEIN,
    ).map { it.first.lowercase() to it.second }

    /** Step 1 without the whitespace collapse, so character offsets still map onto the raw box. */
    fun normaliseKeepingSpacing(raw: String): String {
        var s = raw.normalizeNfkd().lowercase()
        s = s.replace("ß", "ss")
        s = COMBINING_MARKS.replace(s, "")
        s = SEPARATORS.replace(s, " ")
        s = DASHES.replace(s, "-")
        return s
    }

    /** The full step-1 chain: NFKD, lowercase, `ß→ss`, diacritics stripped, separators and
     * unicode dashes mapped, whitespace collapsed. */
    fun normalise(raw: String): String =
        WHITESPACE.replace(normaliseKeepingSpacing(raw), " ").trim()

    /**
     * Splits a normalised line at every `davon` / `of which` that is not at its start, so a
     * merged OCR line such as `kohlenhydrate 58,7 g davon zucker 1,1 g` still yields both
     * nutrients. Offsets are kept relative to the parent line for the column estimate.
     */
    fun segments(normalisedLine: String): List<LineSegment> {
        val starts = mutableListOf(0)
        CHILD_MARKER.findAll(normalisedLine).forEach { if (it.range.first > 0) starts += it.range.first }
        return starts.mapIndexed { i, start ->
            val end = starts.getOrNull(i + 1) ?: normalisedLine.length
            LineSegment(normalisedLine.substring(start, end), start, normalisedLine.length)
        }.filter { it.text.isNotBlank() }
    }

    /**
     * Longest-keyword-first match over [segmentText] (already normalised). An exact hit wins
     * unless a *longer* keyword matches with one edit — that is what keeps
     * `davon gesattlgte fettsauren` out of the `fett` slot.
     */
    fun match(segmentText: String): KeywordHit? {
        val text = WHITESPACE.replace(segmentText, " ").trim()
        val allowed: (NutrientField) -> Boolean =
            if (CHILD_MARKER.containsMatchIn(text)) { f -> f in CHILD_FIELDS } else { _ -> true }

        val exact = KEYWORDS.firstOrNull { (kw, field) -> allowed(field) && text.contains(kw) }
        val fuzzy = KEYWORDS.firstOrNull { (kw, field) -> allowed(field) && fuzzyContains(text, kw) }
            ?: OCR_VARIANTS.firstOrNull { (kw, field) -> allowed(field) && text.contains(kw) }

        return when {
            exact != null && (fuzzy == null || fuzzy.first.length <= exact.first.length) ->
                KeywordHit(exact.second, exact.first, fuzzy = false)
            fuzzy != null -> KeywordHit(fuzzy.second, fuzzy.first, fuzzy = true)
            else -> null
        }
    }

    /** True when some word n-gram of [text] is within one edit of [keyword] (≥ 6 chars only). */
    private fun fuzzyContains(text: String, keyword: String): Boolean {
        if (keyword.length < 6) return false
        val keywordWords = keyword.split(' ').size
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size < keywordWords) return false
        for (i in 0..words.size - keywordWords) {
            val candidate = words.subList(i, i + keywordWords).joinToString(" ")
            if (abs(candidate.length - keyword.length) <= 1 && levenshtein(candidate, keyword) <= 1) return true
        }
        return false
    }

    /** Plain Levenshtein distance, capped at [max] for an early exit. */
    fun levenshtein(a: String, b: String, max: Int = Int.MAX_VALUE): Int {
        if (abs(a.length - b.length) > max) return max + 1
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            if (current.min() > max) return max + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
