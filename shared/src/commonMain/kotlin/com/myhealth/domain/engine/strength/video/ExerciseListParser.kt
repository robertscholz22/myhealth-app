package com.myhealth.domain.engine.strength.video

import com.myhealth.domain.engine.label.Lexicon

/** What one pasted line turned out to be (P26.3). */
enum class LineKind {
    /** A move to match against the exercise library. */
    EXERCISE,

    /** Warm-up, rest, intro, links… shown collapsed in the import screen and can be switched on. */
    SKIP,

    /** "Round 2", "Block B", "Repeat 3×": groups the following (or, for a repeat, the preceding)
     * exercises and may carry a round count. */
    HEADER,
}

/**
 * One line of the pasted list (P26.3). [name] is the move without bullets, numbering, chapter
 * timestamps and amounts, in the original spelling — it is what "New exercise" gets prefilled with.
 * [sets] is the line's own count; [rounds] the count of the block it stands in (1 without one).
 */
data class ParsedLine(
    val raw: String,
    val name: String,
    val kind: LineKind,
    val sets: Int? = null,
    val seconds: Int? = null,
    val reps: Int? = null,
    val perSide: Boolean = false,
    val rounds: Int = 1,
)

/**
 * One distinct move of the list after merging (P26.3): a move that comes back in several rounds is
 * one entry whose [sets] counts every time it is done. [lineIndexes] point into the parsed lines.
 */
data class ListEntry(
    val name: String,
    val sourceLine: String,
    val sets: Int,
    val seconds: Int?,
    val reps: Int?,
    val perSide: Boolean,
    val lineIndexes: List<Int>,
)

/**
 * Pasted exercise list → lines (P26.3). Handles what video descriptions and chapter lists look like:
 * one move per line or a single comma / bullet / pipe separated line; bullets, `1.` / `1)`
 * numbering, chapter timestamps (`0:00`, `12:34 -`) and emoji; amounts in English and German
 * (`40s`, `45 Sek`, `1 min`, `x12`, `12 reps`, `12 Wdh`, `3x`, `3 rounds`, `3 Runden`, `3 × 12`);
 * "each side" / "pro Seite"; rounds and blocks; warm-up, rest and intro lines.
 */
object ExerciseListParser {

    fun parse(text: String): List<ParsedLine> {
        val out = mutableListOf<ParsedLine>()
        splitLines(text).forEach { parseInto(it, out) }
        return applyRounds(out)
    }

    /**
     * The distinct moves of [lines]: EXERCISE lines plus the SKIP lines the owner switched on
     * ([includeSkipped], line indexes). The same move (same [ExerciseMatcher.key]) in several rounds
     * becomes one entry; its sets add up, the first amount and spelling win.
     */
    fun entries(lines: List<ParsedLine>, includeSkipped: Set<Int> = emptySet()): List<ListEntry> {
        val byKey = LinkedHashMap<String, ListEntry>()
        lines.forEachIndexed { index, line ->
            val use = line.kind == LineKind.EXERCISE || (line.kind == LineKind.SKIP && index in includeSkipped)
            if (!use || line.name.isBlank()) return@forEachIndexed
            val key = ExerciseMatcher.key(line.name).ifEmpty { line.name.lowercase() }
            val sets = (line.sets ?: 1) * line.rounds
            val prior = byKey[key]
            byKey[key] = if (prior == null) {
                ListEntry(line.name, line.raw, sets, line.seconds, line.reps, line.perSide, listOf(index))
            } else {
                prior.copy(
                    sets = prior.sets + sets,
                    seconds = prior.seconds ?: line.seconds,
                    reps = prior.reps ?: line.reps,
                    perSide = prior.perSide || line.perSide,
                    lineIndexes = prior.lineIndexes + index,
                )
            }
        }
        return byKey.values.toList()
    }

    // ---- splitting -------------------------------------------------------------------------

    private fun splitLines(text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val single = lines.singleOrNull() ?: return lines
        return if (INLINE_SEPARATOR.containsMatchIn(single)) {
            single.split(INLINE_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            lines
        }
    }

    // ---- one line --------------------------------------------------------------------------

    private fun parseInto(rawLine: String, out: MutableList<ParsedLine>) {
        val raw = rawLine.trim()
        var s = stripDecoration(raw)
        if (s.isEmpty()) return

        if (
            URL.containsMatchIn(s) || s.startsWith("#") || s.startsWith("@") || s.length > MAX_LINE ||
            s.split(' ').count { it.isNotBlank() } > MAX_WORDS
        ) {
            out += ParsedLine(raw, s, LineKind.SKIP)
            return
        }

        // "Round 1: Squats" / "Warm-up: Jumping jacks" — the label goes first, the move is parsed on.
        val colon = s.indexOf(':')
        if (colon in 1 until s.length - 1) {
            val label = s.substring(0, colon)
            val rest = s.substring(colon + 1).trim()
            val labelKey = ExerciseMatcher.normaliseWords(label)
            if (rest.isNotEmpty() && (HEADER_WORDS.matches(labelKey) || isSkipKey(labelKey))) {
                if (HEADER_WORDS.matches(labelKey)) out += ParsedLine(raw, label.trim(), LineKind.HEADER)
                parseInto(rest, out)
                return
            }
        }

        // "Round 1 x2", "Block B – 3 rounds": the label's own number is not an amount.
        val headerLabel = HEADER_PREFIX.find(s)?.takeIf { m ->
            val rest = s.substring(m.range.last + 1)
            rest.isBlank() || !rest.first().isLetter()
        }
        if (headerLabel != null) s = s.substring(headerLabel.range.last + 1)

        var sets: Int? = null
        var seconds: Int? = null
        var reps: Int? = null
        var perSide = false

        SETS_BY_AMOUNT.find(s)?.let { m ->
            sets = m.groupValues[1].toInt()
            val n = m.groupValues[2].toInt()
            if (m.groupValues[3].isNotEmpty()) seconds = n else reps = n
            s = s.removeRange(m.range)
        }
        ROUNDS.find(s)?.let { m ->
            sets = sets ?: m.groupValues[1].toInt()
            s = s.removeRange(m.range)
        }
        MINUTES.find(s)?.let { m ->
            seconds = seconds ?: (m.groupValues[1].replace(',', '.').toDouble() * 60).toInt()
            s = s.removeRange(m.range)
        }
        SECONDS.find(s)?.let { m ->
            seconds = seconds ?: m.groupValues[1].toInt()
            s = s.removeRange(m.range)
        }
        REPS.find(s)?.let { m ->
            reps = reps ?: m.groupValues[1].toInt()
            s = s.removeRange(m.range)
        }
        REPS_OF.find(s)?.let { m ->
            reps = reps ?: m.groupValues[1].toInt()
            s = s.removeRange(m.range)
        }
        REPS_X.find(s)?.let { m ->
            reps = reps ?: m.groupValues[1].toInt()
            s = s.removeRange(m.range)
        }
        SETS_X.find(s)?.let { m ->
            sets = sets ?: m.groupValues[1].toInt()
            s = s.removeRange(m.range)
        }
        PER_SIDE.find(s)?.let { m ->
            perSide = true
            s = s.removeRange(m.range)
        }

        if (headerLabel != null && ExerciseMatcher.normaliseWords(cleanName(s)).let { it.isEmpty() || REPEAT_WORDS.matches(it) }) {
            out += ParsedLine(raw, headerLabel.value.trim(), LineKind.HEADER, sets = sets ?: reps)
            return
        }
        if (headerLabel != null) s = headerLabel.value + s
        val name = cleanName(s)
        val key = ExerciseMatcher.normaliseWords(name)
        when {
            key.isEmpty() || REPEAT_WORDS.matches(key) -> {
                val count = sets ?: reps
                if (count != null) out += ParsedLine(raw, name, LineKind.HEADER, sets = count)
            }
            HEADER_WORDS.matches(key) -> out += ParsedLine(raw, name, LineKind.HEADER, sets = sets ?: reps)
            isSkipKey(key) -> out += ParsedLine(raw, name, LineKind.SKIP, sets, seconds, reps, perSide)
            else -> out += ParsedLine(raw, name, LineKind.EXERCISE, sets, seconds, reps, perSide)
        }
    }

    /** Bullets, emoji, numbering and chapter timestamps off both ends. */
    private fun stripDecoration(line: String): String {
        var s = buildString {
            line.forEach { c -> if (!isDecoration(c)) append(c) }
        }.trim()
        var before: String
        do {
            before = s
            s = s.replace(LEADING_TIMESTAMP, "").replace(TRAILING_TIMESTAMP, "")
                .replace(LEADING_BULLET, "").replace(LEADING_NUMBER, "").trim()
        } while (s != before)
        return s
    }

    private fun isDecoration(c: Char): Boolean {
        val code = c.code
        return c.isSurrogate() || code in 0x2190..0x2BFF || code == 0xFE0F || code == 0x200D || code == 0x20E3
    }

    private fun cleanName(s: String): String =
        s.replace(EMPTY_BRACKETS, " ")
            .replace(MULTI_SPACE, " ")
            .trim { it.isWhitespace() || it in TRIM_CHARS }

    private fun isSkipKey(key: String): Boolean {
        if (key in SKIP_EXACT) return true
        return SKIP_PREFIXES.any { prefix ->
            key.startsWith("$prefix ") && key.removePrefix("$prefix ").split(' ').size <= 2
        }
    }

    // ---- rounds ----------------------------------------------------------------------------

    /**
     * A header with a count ("Round 1 – 3 rounds", "Circuit ×3") sets the rounds of the lines after
     * it; a bare repeat ("Repeat 3×", "3 Runden") after some exercises sets the rounds of the block
     * it closes, and before any sets those of the block it opens.
     */
    private fun applyRounds(lines: List<ParsedLine>): List<ParsedLine> {
        val result = lines.toMutableList()
        var blockStart = 0
        var blockRounds = 1
        var blockHasExercises = false
        fun closeBlock(endExclusive: Int, rounds: Int) {
            for (i in blockStart until endExclusive) {
                val line = result[i]
                if (line.kind != LineKind.HEADER) result[i] = line.copy(rounds = rounds)
            }
        }
        lines.forEachIndexed { index, line ->
            if (line.kind != LineKind.HEADER) {
                if (line.kind == LineKind.EXERCISE) blockHasExercises = true
                return@forEachIndexed
            }
            val count = line.sets
            val isBareRepeat = REPEAT_WORDS.matches(ExerciseMatcher.normaliseWords(line.name))
            if (isBareRepeat && blockHasExercises && count != null) {
                closeBlock(index, count)
                blockStart = index + 1
                blockRounds = 1
            } else {
                closeBlock(index, blockRounds)
                blockStart = index + 1
                blockRounds = count ?: 1
            }
            blockHasExercises = false
        }
        closeBlock(result.size, blockRounds)
        return result
    }

    private const val MAX_LINE = 60
    private const val MAX_WORDS = 7
    private const val TRIM_CHARS = "-–—:|,.;/()[]*•·+=>_\"'"

    private val INLINE_SEPARATOR = Regex("\\s*(?:[•|;·]|(?<!\\d),|,(?!\\d))\\s*")
    private val URL = Regex("(?i)https?://|www\\.")
    private val LEADING_TIMESTAMP = Regex("^[(\\[]?\\d{1,2}:\\d{2}(:\\d{2})?[)\\]]?\\s*([-–—:|]\\s*)?")
    private val TRAILING_TIMESTAMP = Regex("\\s*([-–—|]\\s*)?[(\\[]?\\d{1,2}:\\d{2}(:\\d{2})?[)\\]]?$")
    private val LEADING_BULLET = Regex("^[-*•·▪►>→✓+–—]+\\s*")
    private val LEADING_NUMBER = Regex("^\\d{1,2}\\s*[.)]\\s+|^\\d{1,2}\\s*[.)](?=\\p{L})|^#\\d{1,2}\\s+")
    private val EMPTY_BRACKETS = Regex("\\(\\s*[,/–-]?\\s*\\)|\\[\\s*]")
    private val MULTI_SPACE = Regex("\\s{2,}")

    private const val SEC_UNIT = "(?:s|sec|secs|second|seconds|sek|sekunden)\\.?"
    private val SETS_BY_AMOUNT = Regex("(?i)(?<![\\d:])(\\d{1,2})\\s*[x×]\\s*(\\d{1,3})\\s*($SEC_UNIT)?(?![\\p{L}\\d])")
    private val ROUNDS = Regex("(?i)(?:[x×]\\s*)?(?<![\\d:])(\\d{1,2})\\s*[x×]?\\s*(?:rounds?|runden?|sets?|s(?:ä|ae|a)tze|durchg(?:ä|ae|a)nge|times|mal)(?![\\p{L}])")
    private val MINUTES = Regex("(?i)(?<![\\d:])(\\d{1,2}(?:[.,]5)?)\\s*(?:min|mins|minute|minutes|minuten)\\.?(?![\\p{L}])")
    private val SECONDS = Regex("(?i)(?<![\\d:])(\\d{1,3})\\s*$SEC_UNIT(?![\\p{L}])")
    private val REPS_OF = Regex("(?i)(?<![\\p{L}])(?:of|je|à)\\s+(\\d{1,3})(?![\\d\\p{L}])")
    private val REPS = Regex("(?i)(?<![\\d:])(\\d{1,3})\\s*(?:reps?|repetitions|wdh|wiederholungen)\\.?(?![\\p{L}])")
    private val REPS_X = Regex("(?i)(?<![\\p{L}\\d])[x×]\\s*(\\d{1,3})(?![\\d\\p{L}])")
    private val SETS_X = Regex("(?i)(?<![\\d:])(\\d{1,2})\\s*[x×](?![\\p{L}\\d])")
    private val PER_SIDE = Regex(
        "(?i)\\(?\\b(?:each|per|every|je|pro|jede)\\s+(?:side|leg|arm|seite|bein)\\b\\)?|" +
            "\\(?\\b(?:l\\s*/\\s*r|r\\s*/\\s*l|left\\s*(?:&|and|\\+)\\s*right|links\\s*(?:&|und|\\+)\\s*rechts|beidseitig|both sides|alternating sides)\\b\\)?|" +
            "\\(\\s*each\\s*\\)|/\\s*(?:side|seite)\\b",
    )

    private val HEADER_PREFIX = Regex("(?i)^(?:round|runde|block|circuit|zirkel|part|teil|superset|tabata|emom|amrap|section|abschnitt)(?:\\s+(?:[a-z]|\\d{1,2})(?![\\p{L}\\d]))?")

    /** "round", "round 2", "block b", "circuit 1", "teil 2", "superset a"… */
    private val HEADER_WORDS = Regex("^(?:round|runde|block|circuit|zirkel|part|teil|superset|tabata|emom|amrap|set|satz|workout|section|abschnitt)(?: [a-z0-9]{1,2})?$")

    /** What is left of "Repeat 3 times" / "3 Runden" / "x3" once the amount is cut out. */
    private val REPEAT_WORDS = Regex("^(?:repeat|repeat all|repeat circuit|repeat the circuit|wiederholen|wiederhole|alles wiederholen|total|insgesamt|rounds|runden)?$")

    private val SKIP_EXACT = setOf(
        "warm up", "warmup", "cool down", "cooldown", "rest", "break", "pause", "intro", "introduction",
        "outro", "aufwarmen", "abwarmen", "cool-down", "erholung", "ausruhen", "trinkpause", "water break",
        "transition", "get ready", "finish", "end", "ende", "done", "subscribe", "equipment", "music",
        "musik", "stretch", "stretching", "dehnen", "recovery", "rest and repeat", "rest between rounds",
        "pause zwischen den runden", "start", "welcome", "willkommen", "bonus", "finisher", "rest day",
        "breathe", "breathing", "atmen", "shake out", "ausschutteln",
    )
    private val SKIP_PREFIXES = listOf(
        "warm up", "warmup", "cool down", "cooldown", "rest", "break", "pause", "intro", "outro",
        "aufwarmen", "abwarmen", "erholung", "trinkpause", "water break",
    )
}
