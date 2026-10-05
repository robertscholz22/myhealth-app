package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.StrengthWorkout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * "My equipment" (PLAN §P16, P16.1): rewrites a built-in workout so it only asks for equipment the
 * owner actually has.
 *
 * [substitute] looks for a replacement in [ExerciseCatalog], in this order:
 *
 * 1. the exercise itself, when its own equipment is available (and always when the set is `null`);
 * 2. the first catalog entry with the **same movement pattern and the same primary muscles** that
 *    uses available equipment;
 * 3. the first with the same pattern and **at least one shared primary** muscle;
 * 4. otherwise `null` — the exercise is **dropped**, never silently kept. A gym-only lift the
 *    owner cannot do at home is worse than a shorter workout.
 *
 * A candidate must also be counted / held the same way as the original ([Exercise.isTimed]): a row
 * prescribes exactly one of `reps` / `seconds` (§2.2.7), so swapping a counted press for a hold
 * would produce a row the repository rejects.
 *
 * P17 adds one more wall: a substitution **never crosses the strength/mobility line**
 * ([Exercise.isMobility]). The `MOBILITY` pattern already makes that true by construction — no
 * lift shares it — and the explicit test keeps it true if a later release ever gives a mobility
 * drill a strength pattern (`mob09`).
 *
 * The catalog's own order decides between equally good candidates, which makes every substitution
 * deterministic and testable (`eq01`…`eq05`).
 */
object ExerciseSubstitution {

    /** The replacement for [exercise] given [available], or `null` when nothing fits. */
    fun substitute(exercise: Exercise, available: Set<Equipment>?): Exercise? {
        if (available == null || exercise.equipment in available) return exercise
        val candidates = ExerciseCatalog.ALL.filter {
            it.id != exercise.id &&
                it.pattern == exercise.pattern &&
                it.isTimed == exercise.isTimed &&
                it.isMobility == exercise.isMobility &&
                it.equipment in available
        }
        return candidates.firstOrNull { it.primary == exercise.primary }
            ?: candidates.firstOrNull { it.primary.any { muscle -> muscle in exercise.primary } }
    }

    /**
     * [workout] with every row substituted against [available], the rows that found nothing
     * dropped and the survivors renumbered — a template whose bench press has no home equivalent
     * keeps the rest of the workout (§P16's invariant).
     *
     * `available == null` returns the workout unchanged, down to the row ids, so a profile that
     * never restricted anything materialises exactly the pre-P16 template.
     */
    fun materialise(workout: StrengthWorkout, available: Set<Equipment>?): StrengthWorkout {
        if (available == null) return workout
        val rows = workout.exercises.mapNotNull { row ->
            val original = ExerciseCatalog.byId(row.exerciseId) ?: return@mapNotNull null
            val replacement = substitute(original, available) ?: return@mapNotNull null
            row.copy(
                exerciseId = replacement.id,
                isBodyweight = replacement.equipment == Equipment.BODYWEIGHT,
                // A substitute is never prescribed with the original's load: it is a different
                // implement, and `ProgressionEngine` re-estimates it from body weight.
                loadKg = if (replacement.id == original.id) row.loadKg else null,
            )
        }.mapIndexed { index, row -> row.copy(orderIndex = index) }
        return workout.copy(exercises = rows)
    }
}

/**
 * `profile.availableEquipmentJson` ⇄ a set of [Equipment] (P16.1).
 *
 * `null` (and a blob that is not a JSON array) means **everything**: "my equipment" is an opt-in,
 * and a corrupt value must never leave the owner with an empty catalog. Unknown names inside a
 * well-formed array are ignored one by one, so a downgrade after a future `Equipment` member is
 * added still reads the rest of the set.
 */
object EquipmentSetCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The stored blob as a set, or `null` for "everything" — which an empty array also means.
     */
    fun decode(raw: String?): Set<Equipment>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val names = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        val decoded = names.mapNotNull { element ->
            val name = runCatching { element.jsonPrimitive.content }.getOrNull()
            Equipment.entries.firstOrNull { it.name == name }
        }.toSet()
        return decoded.ifEmpty { null }
    }

    /** A set as the stored blob; `null` (everything) and the full set both encode to `null`. */
    fun encode(equipment: Set<Equipment>?): String? {
        if (equipment == null || equipment.containsAll(Equipment.entries)) return null
        val ordered = Equipment.entries.filter { it in equipment }
        if (ordered.isEmpty()) return null
        return ordered.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"${it.name}\"" }
    }
}
