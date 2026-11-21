package com.myhealth.ui.strength

import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.engine.strength.video.ExerciseListParser
import com.myhealth.domain.engine.strength.video.ExerciseMatcher
import com.myhealth.domain.engine.strength.video.LineKind
import com.myhealth.domain.engine.strength.video.MatchResult
import com.myhealth.domain.engine.strength.video.MatchStatus
import com.myhealth.domain.engine.strength.video.ParsedLine
import com.myhealth.domain.engine.strength.video.VideoLink
import com.myhealth.domain.engine.strength.video.VideoRow
import com.myhealth.domain.engine.strength.video.VideoWorkoutBuilder
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.ui.common.body.highlightFor

enum class VideoImportStep { VIDEO, CHECK }

/** What the import screen shows on a row's chip (P26.4). */
enum class ImportRowStatus { MATCHED, CHECK, NOT_FOUND, EXCLUDED }

/**
 * One distinct move of the pasted list on the "Check exercises" step (P26.4). [chosenId] is the
 * exercise it will be saved as: the matcher's best guess, or what the owner picked
 * ([chosenByHand] — those names are learned on save).
 */
data class ImportRow(
    val key: String,
    val name: String,
    val sourceLine: String,
    val sets: Int,
    val reps: Int?,
    val seconds: Int?,
    val perSide: Boolean,
    val match: MatchResult,
    val chosenId: String?,
    val chosenByHand: Boolean = false,
    val excluded: Boolean = false,
) {
    val status: ImportRowStatus
        get() = when {
            excluded -> ImportRowStatus.EXCLUDED
            chosenId == null -> ImportRowStatus.NOT_FOUND
            chosenByHand -> ImportRowStatus.MATCHED
            match.status == MatchStatus.MATCHED -> ImportRowStatus.MATCHED
            else -> ImportRowStatus.CHECK
        }
}

data class VideoImportUiState(
    val step: VideoImportStep = VideoImportStep.VIDEO,
    val loading: Boolean = true,
    /** The workout being re-checked, or `null` for a new one. */
    val workoutId: Long? = null,
    val url: String = "",
    val title: String = "",
    val titleLoading: Boolean = false,
    val name: String = "",
    /** Once the owner typed a name, a fetched title no longer overwrites it. */
    val nameEdited: Boolean = false,
    val durationMin: Int? = null,
    val text: String = "",
    val lines: List<ParsedLine> = emptyList(),
    val includedSkips: Set<Int> = emptySet(),
    val rows: List<ImportRow> = emptyList(),
    val kindOverride: StrengthWorkoutKind? = null,
    val showErrors: Boolean = false,
    val confirmUnmatched: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    val urlError: Boolean get() = !VideoLink.isWebLink(url.trim())
    val nameError: Boolean get() = name.isBlank()
    val textError: Boolean get() = ExerciseListParser.parse(text).none { it.kind == LineKind.EXERCISE }
    val canContinue: Boolean get() = !urlError && !nameError && !textError

    val skippedLines: List<Pair<Int, ParsedLine>>
        get() = lines.withIndex().filter { it.value.kind == LineKind.SKIP }.map { it.index to it.value }

    val unmatchedCount: Int get() = rows.count { it.status == ImportRowStatus.NOT_FOUND }
    val checkCount: Int get() = rows.count { it.status == ImportRowStatus.CHECK }

    fun videoRows(library: ExerciseLibrary): List<VideoRow> = rows.filter { !it.excluded }.mapNotNull { row ->
        val exercise = row.chosenId?.let(library::byId) ?: return@mapNotNull null
        VideoRow(exercise, row.sets, row.reps, row.seconds, row.perSide, row.sourceLine)
    }

    fun kind(library: ExerciseLibrary): StrengthWorkoutKind =
        kindOverride ?: VideoWorkoutBuilder.deriveKind(videoRows(library))

    fun highlight(library: ExerciseLibrary): Map<MuscleGroup, Float> {
        val result = mutableMapOf<MuscleGroup, Float>()
        videoRows(library).forEach { row ->
            highlightFor(row.exercise).forEach { (m, v) -> result[m] = maxOf(result[m] ?: 0f, v) }
        }
        return result
    }
}

/** The pure steps of the import screen (P26.4), kept apart from the ViewModel for tests. */
object VideoImport {

    /**
     * Parses [text] and matches every distinct move. A row whose pasted line (or, failing that,
     * whose name) the owner already decided keeps that decision: [previousBySourceLine] are the
     * saved rows of a re-checked workout, [current] the rows on screen before a re-parse.
     */
    fun rows(
        text: String,
        includedSkips: Set<Int>,
        library: ExerciseLibrary,
        aliases: Map<String, String>,
        previousBySourceLine: Map<String, String> = emptyMap(),
        current: List<ImportRow> = emptyList(),
    ): Pair<List<ParsedLine>, List<ImportRow>> {
        val lines = ExerciseListParser.parse(text)
        val matcher = ExerciseMatcher(library, aliases)
        val currentByKey = current.associateBy { it.key }
        val rows = ExerciseListParser.entries(lines, includedSkips).map { entry ->
            val key = ExerciseMatcher.key(entry.name).ifEmpty { entry.name.lowercase() }
            val match = matcher.match(entry.name)
            val base = ImportRow(
                key = key,
                name = entry.name,
                sourceLine = entry.sourceLine,
                sets = entry.sets,
                reps = entry.reps,
                seconds = entry.seconds,
                perSide = entry.perSide,
                match = match,
                chosenId = match.best?.id,
            )
            val kept = currentByKey[key]
            val previousId = previousBySourceLine[entry.sourceLine]?.takeIf { library.byId(it) != null }
            when {
                kept != null && (kept.chosenByHand || kept.excluded) ->
                    base.copy(chosenId = kept.chosenId, chosenByHand = kept.chosenByHand, excluded = kept.excluded)
                previousId != null && previousId != base.chosenId -> base.copy(chosenId = previousId, chosenByHand = true)
                else -> base
            }
        }
        return lines to rows
    }

    fun choose(rows: List<ImportRow>, key: String, exercise: Exercise): List<ImportRow> =
        rows.map { if (it.key == key) it.copy(chosenId = exercise.id, chosenByHand = true, excluded = false) else it }

    fun setExcluded(rows: List<ImportRow>, key: String, excluded: Boolean): List<ImportRow> =
        rows.map { if (it.key == key) it.copy(excluded = excluded) else it }

    fun setAmounts(rows: List<ImportRow>, key: String, sets: Int, reps: Int?, seconds: Int?): List<ImportRow> =
        rows.map { if (it.key == key) it.copy(sets = sets.coerceAtLeast(1), reps = reps, seconds = seconds) else it }

    /**
     * Names to learn on save: every row matched by hand whose name is not already how the matcher
     * finds that exercise (normalised name → exercise id).
     */
    fun learnedAliases(rows: List<ImportRow>): Map<String, String> = rows
        .filter { it.chosenByHand && !it.excluded && it.chosenId != null }
        .filter { !(it.match.status == MatchStatus.MATCHED && it.match.best?.id == it.chosenId) }
        .associate { it.key to it.chosenId!! }
}
