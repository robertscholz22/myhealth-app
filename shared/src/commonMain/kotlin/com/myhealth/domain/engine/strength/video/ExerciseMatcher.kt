package com.myhealth.domain.engine.strength.video

import com.myhealth.domain.engine.label.Lexicon
import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import kotlin.math.max

/** How sure the matcher is about a pasted name (P26.3). */
enum class MatchStatus {
    /** Score ≥ 0.85 (or a learned name): taken as is. */
    MATCHED,

    /** 0.60 – 0.85: the best guess is pre-selected, the owner confirms or picks another. */
    CHECK,

    /** Below 0.60: nothing close; search or create a new exercise. */
    NOT_FOUND,
}

data class MatchCandidate(val exercise: Exercise, val score: Double)

data class MatchResult(
    val status: MatchStatus,
    /** Up to three candidates, best first; for MATCHED the first one is the match. */
    val candidates: List<MatchCandidate>,
    /** True when the match came from a name the owner matched by hand before. */
    val fromAlias: Boolean = false,
) {
    val best: Exercise? get() = if (status == MatchStatus.NOT_FOUND) null else candidates.firstOrNull()?.exercise
}

/**
 * Pasted name → exercise of the [library] (P26.3). Order: a learned name ([aliases], normalised
 * name → exercise id) wins; then an exact name or [ExerciseSynonyms] entry (compared without
 * spaces, so "pushups" = "push-up"); then a fuzzy score — token overlap with a typo allowance,
 * spelling distance of the whole name, containment, and a small equipment bonus.
 */
class ExerciseMatcher(
    private val library: ExerciseLibrary,
    private val aliases: Map<String, String> = emptyMap(),
) {

    private class Name(val exercise: Exercise, val key: String, val compact: String, val tokens: List<String>)

    private val names: List<Name> = library.all.flatMap { exercise ->
        (listOf(exercise.name) + ExerciseSynonyms.of(exercise.id)).mapNotNull { raw ->
            val key = key(raw)
            if (key.isEmpty()) null else Name(exercise, key, key.replace(" ", ""), key.split(' '))
        }
    }

    /** Exact names by compact key. Two catalog names can share one ("Band pull-apart" and "Band
     * pull-apart (slow)" once "slow" is dropped); [exact] then picks by the unfiltered words. */
    private val exactByCompact: Map<String, List<Exercise>> = names.groupBy { it.compact }
        .mapValues { (_, list) -> list.map { it.exercise }.distinctBy { it.id } }

    private fun exact(compact: String, name: String): Exercise? {
        val found = exactByCompact[compact] ?: return null
        if (found.size == 1) return found.single()
        val words = normaliseWords(name).replace(" ", "")
        return found.firstOrNull { normaliseWords(it.name).replace(" ", "") == words }
            ?: found.firstOrNull { !it.isMobility }
            ?: found.first()
    }

    fun match(name: String): MatchResult {
        val key = key(name)
        if (key.isEmpty()) return MatchResult(MatchStatus.NOT_FOUND, emptyList())

        aliases[key]?.let { id -> library.byId(id) }?.let { exercise ->
            return MatchResult(MatchStatus.MATCHED, listOf(MatchCandidate(exercise, 1.0)), fromAlias = true)
        }
        val compact = key.replace(" ", "")
        exact(compact, name)?.let { exercise ->
            val others = ranked(key).filter { it.exercise.id != exercise.id }.take(MAX_CANDIDATES - 1)
            return MatchResult(MatchStatus.MATCHED, listOf(MatchCandidate(exercise, 1.0)) + others)
        }

        val ranked = ranked(key).take(MAX_CANDIDATES)
        val top = ranked.firstOrNull() ?: return MatchResult(MatchStatus.NOT_FOUND, emptyList())
        val tie = ranked.getOrNull(1)?.let { top.score - it.score < TIE_MARGIN } == true
        val status = when {
            top.score >= MATCHED_AT && !tie -> MatchStatus.MATCHED
            top.score >= CHECK_AT -> MatchStatus.CHECK
            else -> MatchStatus.NOT_FOUND
        }
        val shown = if (status == MatchStatus.NOT_FOUND) ranked.filter { it.score >= SUGGEST_AT } else ranked
        return MatchResult(status, shown)
    }

    private fun ranked(key: String): List<MatchCandidate> {
        val tokens = key.split(' ')
        val compact = key.replace(" ", "")
        val equipment = equipmentWords(tokens)
        val best = HashMap<String, MatchCandidate>()
        names.forEach { n ->
            var s = score(tokens, compact, n)
            if (s <= 0.0) return@forEach
            if (equipment != null) s += if (n.exercise.equipment == equipment) EQUIPMENT_BONUS else -EQUIPMENT_BONUS
            s = s.coerceIn(0.0, 0.99)
            val prior = best[n.exercise.id]
            if (prior == null || s > prior.score) best[n.exercise.id] = MatchCandidate(n.exercise, s)
        }
        return best.values.sortedWith(compareByDescending<MatchCandidate> { it.score }.thenBy { it.exercise.name })
    }

    private fun score(q: List<String>, qCompact: String, n: Name): Double {
        var s = 0.0
        // Whole-name spelling: "squatts", "burpes", "liegestuetzen".
        if (qCompact.length >= 5 && n.compact.length >= 5) {
            val d = Lexicon.levenshtein(qCompact, n.compact, max = 2)
            if (d <= 2) s = max(s, 1.0 - 0.06 * d)
        }
        // Soft Jaccard on tokens, a typo allowed per longer token.
        var hits = 0.0
        q.forEach { qt -> hits += n.tokens.maxOf { tokenSimilarity(qt, it) } }
        val union = q.size + n.tokens.size - hits
        if (union > 0) s = max(s, hits / union)
        // Containment: "glute bridge pulses" ⊃ "glute bridge"; "squat" ⊂ "goblet squat".
        val qSet = q.toSet()
        val nSet = n.tokens.toSet()
        if (nSet.isNotEmpty() && qSet.containsAll(nSet)) s = max(s, 0.6 + 0.3 * nSet.size / qSet.size)
        if (qSet.isNotEmpty() && nSet.containsAll(qSet)) s = max(s, 0.55 + 0.3 * qSet.size / nSet.size)
        return s
    }

    private fun tokenSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 4 || b.length < 4) return 0.0
        val allowed = if (max(a.length, b.length) >= 7) 2 else 1
        val d = Lexicon.levenshtein(a, b, max = allowed)
        return if (d <= allowed) 1.0 - d.toDouble() / max(a.length, b.length) else 0.0
    }

    private fun equipmentWords(tokens: List<String>): Equipment? = tokens.firstNotNullOfOrNull { EQUIPMENT_WORDS[it] }

    companion object {
        const val MATCHED_AT = 0.85
        const val CHECK_AT = 0.60
        private const val SUGGEST_AT = 0.40
        private const val TIE_MARGIN = 0.005
        private const val MAX_CANDIDATES = 3
        private const val EQUIPMENT_BONUS = 0.05

        /**
         * The comparison form of a name — also the key a learned name is stored under: lowercase,
         * accents and ß folded, punctuation gone, filler words dropped, plurals singular.
         */
        fun key(name: String): String =
            normaliseWords(name).split(' ').filter { it.isNotEmpty() && it !in FILLERS }
                .map(::singular).joinToString(" ")

        /** Lowercase, folded, letters and digits only, single spaces. */
        fun normaliseWords(name: String): String =
            NON_ALNUM.replace(Lexicon.normalise(name.replace("’", "'").replace("'", "")), " ").trim()

        fun singular(word: String): String = when {
            word.length <= 2 -> word
            word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
            word.endsWith("sses") || word.endsWith("shes") || word.endsWith("ches") || word.endsWith("xes") -> word.dropLast(2)
            word.endsWith("ss") || word.endsWith("us") -> word
            word.endsWith("s") -> word.dropLast(1)
            else -> word
        }

        private val NON_ALNUM = Regex("[^a-z0-9]+")

        private val FILLERS = setOf(
            "a", "an", "the", "with", "and", "on", "to", "in", "of", "for", "alternating", "alternate", "alt",
            "slow", "slowly", "fast", "quick", "explosive", "controlled", "exercise", "variation", "mit", "und",
            "der", "die", "das", "ein", "eine", "im", "am", "auf", "zur", "zum", "ubung", "uebung", "langsam",
            "schnell", "abwechselnd", "your", "my", "mini", "small", "big",
        )

        private val EQUIPMENT_WORDS: Map<String, Equipment> = mapOf(
            "dumbbell" to Equipment.DUMBBELL, "db" to Equipment.DUMBBELL, "kurzhantel" to Equipment.DUMBBELL,
            "hantel" to Equipment.DUMBBELL, "barbell" to Equipment.BARBELL, "langhantel" to Equipment.BARBELL,
            "kettlebell" to Equipment.KETTLEBELL, "kb" to Equipment.KETTLEBELL,
            "band" to Equipment.BAND, "banded" to Equipment.BAND, "theraband" to Equipment.BAND,
            "cable" to Equipment.CABLE, "kabel" to Equipment.CABLE,
        )
    }
}
