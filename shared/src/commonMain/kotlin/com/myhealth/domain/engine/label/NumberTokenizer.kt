package com.myhealth.domain.engine.label

/** The units a label number can carry (§3.6.1 step 2). */
enum class NumberUnit { KJ, KCAL, MG, UG, G, ML }

/**
 * One number found on a line: its [value] (decimal comma already resolved), its [unit] if the
 * scan caught one, whether it was written as an upper bound (`< 0,5 g`), and the character range
 * of the numeral inside the line — the column estimate of §3.6.1 step 4 needs it.
 */
data class NumberToken(
    val value: Double,
    val unit: NumberUnit?,
    val isUpperBound: Boolean,
    val start: Int,
    val end: Int,
) {
    val centerOffset: Int get() = (start + end) / 2
}

/**
 * Step 2 of §3.6.1, verbatim: the number regex with the `<`/`≤` upper-bound prefix, comma
 * decimals, the kJ thousands guard, and rejection of anything with more than four integer digits.
 */
object NumberTokenizer {

    private val PATTERN = Regex(
        """(?<lt>[<≤]\s*)?(?<num>\d{1,4}(?:[.,]\d{1,3})?)\s*(?<unit>kj|kcal|cal|mg|µg|μg|ug|g|ml)?""",
        RegexOption.IGNORE_CASE,
    )

    private val PARENS = Regex("""\(([^)]*)\)""")

    fun tokenize(text: String): List<NumberToken> =
        PATTERN.findAll(text).mapNotNull { match -> toToken(text, match) }.toList()

    private fun toToken(text: String, match: MatchResult): NumberToken? {
        val numeral = match.groups["num"] ?: return null
        // `MatchGroup.range` is JVM-only; `num` starts right after the optional `lt` prefix.
        val numStart = match.range.first + (match.groups["lt"]?.value?.length ?: 0)
        val range = numStart until numStart + numeral.value.length
        val unit = match.groups["unit"]?.value?.let(::unitOf)
        val before = text.getOrNull(range.first - 1)
        val after = text.getOrNull(range.last + 1)
        // > 4 integer digits are rejected: the regex would otherwise keep the first four.
        if (before != null && before.isDigit()) return null
        if (after != null && after.isDigit()) return null
        // A numeral glued to a word is scanner noise, not a value (`Bal1aststoffe` is not a 1).
        if (before != null && before.isLetter()) return null
        if (unit == null && after != null && after.isLetter()) return null

        val raw = numeral.value
        val fraction = raw.substringAfter(',', raw.substringAfter('.', ""))
        // Thousands guard: `1.234 kj` is 1234, not 1.234 kJ.
        val thousands = unit == NumberUnit.KJ && fraction.length == 3
        val value = if (thousands) {
            raw.filter { it.isDigit() }.toDouble()
        } else {
            raw.replace(',', '.').toDouble()
        }
        return NumberToken(
            value = value,
            unit = unit,
            isUpperBound = match.groups["lt"] != null,
            start = range.first,
            end = range.last + 1,
        )
    }

    private fun unitOf(raw: String): NumberUnit = when (raw.lowercase()) {
        "kj" -> NumberUnit.KJ
        "kcal", "cal" -> NumberUnit.KCAL
        "mg" -> NumberUnit.MG
        "µg", "μg", "ug" -> NumberUnit.UG
        "ml" -> NumberUnit.ML
        else -> NumberUnit.G
    }

    /** The text inside the first pair of parentheses, e.g. `pro portion (30 g)` -> `30 g`. */
    fun parenthesised(text: String): String? = PARENS.find(text)?.groupValues?.get(1)?.trim()

    /** Grams named inside parentheses, used for the serving column's `servingGrams`. */
    fun gramsIn(text: String): Double? = tokenize(text)
        .firstOrNull { it.unit == NumberUnit.G || it.unit == NumberUnit.ML }
        ?.value
}
