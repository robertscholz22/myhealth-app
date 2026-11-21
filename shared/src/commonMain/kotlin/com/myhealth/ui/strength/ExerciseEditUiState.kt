package com.myhealth.ui.strength

import com.myhealth.domain.engine.label.Lexicon
import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.UserExercise

/**
 * The exercise editor's draft (P26.2) — one of the owner's own exercises, new or existing.
 *
 * Muscles are picked on the body figure (tap: primary → secondary → off) or with the chip rows;
 * [primary] and [secondary] stay disjoint whichever way they were set. A mobility exercise is
 * always timed and has the `MOBILITY` pattern, as every catalog mobility drill does.
 */
data class ExerciseEditDraft(
    /** `null` for a new exercise; the id is drawn on the first save. */
    val id: String? = null,
    val name: String = "",
    val primary: Set<MuscleGroup> = emptySet(),
    val secondary: Set<MuscleGroup> = emptySet(),
    val mobility: Boolean = false,
    val equipment: Equipment = Equipment.BODYWEIGHT,
    /** A strength exercise's pattern; [MovementPattern.OTHER] is "not specified". */
    val pattern: MovementPattern = MovementPattern.OTHER,
    val isTimed: Boolean = false,
    val unilateral: Boolean = false,
    val notes: String = "",
    val archived: Boolean = false,
    val createdAtMillis: Long = 0L,
) {
    val isNew: Boolean get() = id == null

    val nameMissing: Boolean get() = name.isBlank()

    val primaryMissing: Boolean get() = primary.isEmpty()

    val canSave: Boolean get() = !nameMissing && !primaryMissing

    /** What the body figure shows: primary full strength, secondary half. */
    val highlight: Map<MuscleGroup, Float>
        get() = secondary.associateWith { 0.5f } + primary.associateWith { 1.0f }

    /** One tap on the figure: off → primary → secondary → off. */
    fun cycleMuscle(muscle: MuscleGroup): ExerciseEditDraft = when (muscle) {
        in primary -> copy(primary = primary - muscle, secondary = secondary + muscle)
        in secondary -> copy(secondary = secondary - muscle)
        else -> copy(primary = primary + muscle)
    }

    fun togglePrimary(muscle: MuscleGroup): ExerciseEditDraft =
        if (muscle in primary) copy(primary = primary - muscle)
        else copy(primary = primary + muscle, secondary = secondary - muscle)

    fun toggleSecondary(muscle: MuscleGroup): ExerciseEditDraft =
        if (muscle in secondary) copy(secondary = secondary - muscle)
        else copy(secondary = secondary + muscle, primary = primary - muscle)

    fun withMobility(mobility: Boolean): ExerciseEditDraft = copy(mobility = mobility)

    /** The stored exercise; [newId] is only drawn for a new one. */
    fun toUserExercise(newId: () -> String, nowMillis: Long): UserExercise {
        val pattern = if (mobility) MovementPattern.MOBILITY else pattern.takeIf { it != MovementPattern.MOBILITY } ?: MovementPattern.OTHER
        return UserExercise(
            exercise = Exercise(
                id = id ?: newId(),
                name = name.trim(),
                primary = primary,
                secondary = secondary - primary,
                equipment = equipment,
                pattern = pattern,
                unilateral = unilateral,
                isTimed = mobility || isTimed,
                cue = notes.trim(),
            ),
            notes = notes.trim().takeIf { it.isNotEmpty() },
            archived = archived,
            createdAtMillis = if (isNew) nowMillis else createdAtMillis,
            updatedAtMillis = nowMillis,
        )
    }

    /**
     * Another exercise in [library] whose name is the same once normalised (case, accents,
     * punctuation, a plural "s") — shown as "already exists" so the owner can pick that one.
     */
    fun duplicateIn(library: ExerciseLibrary): Exercise? {
        val key = nameKey(name)
        if (key.isEmpty()) return null
        return library.all.firstOrNull { it.id != id && nameKey(it.name) == key }
    }

    companion object {
        fun of(existing: UserExercise): ExerciseEditDraft {
            val e = existing.exercise
            return ExerciseEditDraft(
                id = e.id,
                name = e.name,
                primary = e.primary,
                secondary = e.secondary,
                mobility = e.isMobility,
                equipment = e.equipment,
                pattern = if (e.isMobility) MovementPattern.OTHER else e.pattern,
                isTimed = e.isTimed,
                unilateral = e.unilateral,
                notes = existing.notes.orEmpty(),
                archived = existing.archived,
                createdAtMillis = existing.createdAtMillis,
            )
        }

        /** "Push-Ups" and "push up" are the same name. */
        internal fun nameKey(name: String): String =
            Lexicon.normalise(name).replace(NON_ALNUM, " ").split(' ').filter { it.isNotEmpty() }
                .joinToString(" ") { if (it.length >= 3 && it.endsWith('s') && !it.endsWith("ss")) it.dropLast(1) else it }

        private val NON_ALNUM = Regex("[^a-z0-9]+")

        /** The patterns a strength exercise may pick, "not specified" first. */
        val STRENGTH_PATTERNS: List<MovementPattern> =
            listOf(MovementPattern.OTHER) + MovementPattern.entries.filter {
                it != MovementPattern.OTHER && it != MovementPattern.MOBILITY
            }
    }
}

/** The editor screen's state: the draft plus what the screen needs to offer archive / delete. */
data class ExerciseEditUiState(
    val draft: ExerciseEditDraft = ExerciseEditDraft(),
    val loading: Boolean = true,
    /** Workout rows that use the exercise; deleting is only offered at 0. */
    val usageCount: Int = 0,
    val duplicate: Exercise? = null,
    val showErrors: Boolean = false,
    val saving: Boolean = false,
)
