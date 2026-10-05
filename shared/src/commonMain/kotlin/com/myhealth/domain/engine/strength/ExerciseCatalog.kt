package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup

/**
 * The exercise catalog (PLAN §3.12.1) — a Kotlin object, deliberately not a JSON asset: `domain/`
 * is Android-free and cannot open `assets/`, and a compile-time-checked `Set<MuscleGroup>` is
 * worth more than a stringly-typed blob that would need a loader, a parser and its own tests.
 *
 * The entries live in four files for R10 ([UPPER_BODY_EXERCISES], [LOWER_BODY_EXERCISES],
 * [CORE_EXERCISES] and, since P17, [MOBILITY_EXERCISES]); this object is the only place that joins
 * them and the only API the rest of the app uses.
 *
 * **Ids are permanent.** `strength_workout_exercise.exerciseId` stores them, so an entry may be
 * renamed or re-cued in code, but never re-identified — and an id that disappears would orphan a
 * saved workout row, which is why [byId] returns `null` rather than throwing.
 */
object ExerciseCatalog {

    /**
     * Every entry, upper body → lower body → trunk → mobility, in the order the four files declare
     * them. Mobility is **appended**, so every list position the strength half ever had is kept
     * and `ExerciseSubstitution`'s "first candidate wins" stays the choice it was before P17.
     */
    val ALL: List<Exercise> =
        UPPER_BODY_EXERCISES + LOWER_BODY_EXERCISES + CORE_EXERCISES + MOBILITY_EXERCISES

    /** The lifts — everything that is not a P17 mobility drill. */
    val strength: List<Exercise> = ALL.filter { !it.isMobility }

    /** The P17 mobility drills: stretches, joint rotations and foam-roll work, all timed. */
    val mobility: List<Exercise> = ALL.filter { it.isMobility }

    private val byIdMap: Map<String, Exercise> = ALL.associateBy { it.id }

    init {
        // The catalog is data typed by hand; a duplicated id would silently shadow an entry and
        // make a stored workout row point at the wrong exercise.
        check(byIdMap.size == ALL.size) { "ExerciseCatalog has duplicate ids" }
    }

    /** The entry with this stored id, or `null` if a saved row names one the catalog dropped. */
    fun byId(id: String): Exercise? = byIdMap[id]

    /**
     * Free-text search over the exercise name **and** the names of the muscle groups it trains
     * (`ex09`: "squat" and "quads" both find the back squat). Case-insensitive, whitespace- and
     * underscore-insensitive (`lower back` matches `LOWER_BACK`).
     *
     * Name matches come first, then muscle-only matches; within each half the catalog's own order
     * is kept, so the result is deterministic. A blank query returns [ALL].
     */
    fun search(query: String, kind: ExerciseKind? = null): List<Exercise> {
        val pool = of(kind)
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return pool
        val byName = pool.filter { it.name.lowercase().contains(needle) }
        val byMuscle = pool.filter { it !in byName && it.matchesMuscleName(needle) }
        return byName + byMuscle
    }

    /** The half of the catalog [kind] names; [ALL] for `null` ("any"), which is the chip "All". */
    fun of(kind: ExerciseKind?): List<Exercise> = when (kind) {
        null -> ALL
        ExerciseKind.STRENGTH -> strength
        ExerciseKind.MOBILITY -> mobility
    }

    /**
     * The four filters of the exercise list screen, `AND`-ed; every `null` argument is "any".
     * [muscle] matches a primary **or** a secondary group — the screen's chip says "trains", not
     * "targets".
     */
    fun filter(
        equipment: Equipment? = null,
        pattern: MovementPattern? = null,
        muscle: MuscleGroup? = null,
        kind: ExerciseKind? = null,
    ): List<Exercise> = of(kind).filter { exercise ->
        (equipment == null || exercise.equipment == equipment) &&
            (pattern == null || exercise.pattern == pattern) &&
            (muscle == null || muscle in exercise.primary || muscle in exercise.secondary)
    }

    /** Every entry that trains [muscle] as its primary — what the body figure's tap opens. */
    fun byPrimary(muscle: MuscleGroup): List<Exercise> = ALL.filter { muscle in it.primary }

    private fun Exercise.matchesMuscleName(needle: String): Boolean =
        allMuscles.any { group -> group.searchName.contains(needle) }

    /** `LOWER_BACK` → `"lower back"`, so a user types what they see, not what the enum is called. */
    private val MuscleGroup.searchName: String
        get() = name.lowercase().replace('_', ' ')
}

/** The exercise list screen's chip row (P17): "All" is `null`, the other two are these. */
enum class ExerciseKind { STRENGTH, MOBILITY }
