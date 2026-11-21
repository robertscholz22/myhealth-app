package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.UserExercise
import kotlin.concurrent.Volatile

/**
 * P26: the catalog plus the owner's own exercises — what every exercise lookup goes through.
 *
 * Lists ([all], [search], [filter]) show the catalog first, then the owner's exercises that are
 * not archived, by name. [byId] also resolves archived ones: a saved workout keeps working after
 * its exercise was archived.
 */
class ExerciseLibrary(userExercises: List<UserExercise> = emptyList()) {

    /** The owner's exercises, archived included, by name. */
    val userExercises: List<UserExercise> = userExercises.sortedBy { it.exercise.name.lowercase() }

    /** The owner's exercises that lists and pickers show. */
    val custom: List<Exercise> = this.userExercises.filter { !it.archived }.map { it.exercise }

    private val userById: Map<String, UserExercise> = this.userExercises.associateBy { it.exercise.id }

    /** Catalog then own exercises: what the exercise list and the pickers offer. */
    val all: List<Exercise> = ExerciseCatalog.ALL + custom

    fun byId(id: String): Exercise? = ExerciseCatalog.byId(id) ?: userById[id]?.exercise

    fun userExercise(id: String): UserExercise? = userById[id]

    fun of(kind: ExerciseKind?): List<Exercise> = when (kind) {
        null -> all
        ExerciseKind.STRENGTH -> all.filter { !it.isMobility }
        ExerciseKind.MOBILITY -> all.filter { it.isMobility }
    }

    /** [ExerciseCatalog.search] over the catalog and the owner's exercises, same rules. */
    fun search(query: String, kind: ExerciseKind? = null): List<Exercise> {
        val pool = of(kind)
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return pool
        val byName = pool.filter { it.name.lowercase().contains(needle) }
        val byMuscle = pool.filter { exercise ->
            exercise !in byName &&
                exercise.allMuscles.any { it.name.lowercase().replace('_', ' ').contains(needle) }
        }
        return byName + byMuscle
    }

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

    companion object {
        /** The catalog alone: what every lookup sees until the owner's exercises are loaded. */
        val CATALOG_ONLY: ExerciseLibrary = ExerciseLibrary()
    }
}

/**
 * P26: the library currently in use, for code that looks exercises up by id without a
 * repository at hand (the pure engines, the body figure, row labels).
 *
 * The user-exercise repository [install]s a new immutable snapshot whenever the table changes; a
 * reader always sees one whole snapshot. Until then — and in every test that never installs
 * one — it is [ExerciseLibrary.CATALOG_ONLY], so the catalog-only behaviour (and every fixture
 * built on it) is unchanged.
 */
object ExerciseRegistry {

    @Volatile
    var library: ExerciseLibrary = ExerciseLibrary.CATALOG_ONLY
        private set

    fun install(library: ExerciseLibrary) {
        this.library = library
    }

    /** Back to the catalog alone (tests). */
    fun reset() {
        library = ExerciseLibrary.CATALOG_ONLY
    }

    fun byId(id: String): Exercise? = library.byId(id)
}

/** P26: ids for the owner's exercises — `USER_` plus 12 characters without look-alikes. */
object UserExerciseIds {
    private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun next(random: kotlin.random.Random = kotlin.random.Random.Default): String =
        Exercise.USER_EXERCISE_PREFIX + (1..12).map { CHARS[random.nextInt(CHARS.length)] }.joinToString("")
}
