package com.myhealth.domain.share

import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MealTemplate
import com.myhealth.domain.model.MealTemplateItem
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.model.UserExercise
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * P27: what one library item carries, per kind, as versioned JSON ([SCHEMA]).
 *
 * Only the content itself travels. Personal state stays on the device: use counts, last used,
 * favourite, archived, "use in suggestions", and an Open Food Facts product's raw payload.
 *
 * Decoding is lenient: unknown keys from a newer app are ignored and an unknown enum name falls back
 * (or is dropped from a muscle set), so an item written by a newer version still imports.
 */
object SharePayloads {

    const val SCHEMA: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Serializable
    data class ExercisePayload(
        /** The author's `USER_…` id: random, so it is kept on import and workout rows still match. */
        val id: String,
        val name: String,
        val primary: List<String>,
        val secondary: List<String> = emptyList(),
        val equipment: String,
        val pattern: String,
        val isTimed: Boolean = false,
        val unilateral: Boolean = false,
        val notes: String? = null,
    )

    @Serializable
    data class IngredientPayload(
        val name: String,
        val brand: String? = null,
        val barcode: String? = null,
        val basis: String,
        val pieceGrams: Double? = null,
        val servingGrams: Double? = null,
        val servingLabel: String? = null,
        val kcal: Double,
        val proteinG: Double? = null,
        val carbsG: Double? = null,
        val sugarG: Double? = null,
        val fatG: Double? = null,
        val satFatG: Double? = null,
        val fiberG: Double? = null,
        val saltG: Double? = null,
        val sodiumG: Double? = null,
        val source: String = "MANUAL",
    )

    @Serializable
    data class MealItemPayload(
        val ingredientShareId: String,
        val quantity: Double,
        val unit: String,
        val sortOrder: Int = 0,
    )

    @Serializable
    data class MealPayload(
        val name: String,
        val defaultSlot: String? = null,
        val note: String? = null,
        val servings: Double = 1.0,
        val items: List<MealItemPayload> = emptyList(),
    )

    @Serializable
    data class WorkoutRowPayload(
        /** A catalog id, or an own exercise's `USER_…` id (then [exerciseShareId] is set). */
        val exerciseId: String,
        val exerciseShareId: String? = null,
        val sets: Int,
        val reps: Int? = null,
        val seconds: Int? = null,
        val loadKg: Double? = null,
        val isBodyweight: Boolean = false,
        val restSec: Int? = null,
        val note: String? = null,
        val sourceLine: String? = null,
    )

    @Serializable
    data class WorkoutPayload(
        val name: String,
        val kind: String,
        val notes: String? = null,
        val videoUrl: String? = null,
        val videoTitle: String? = null,
        val durationMin: Int? = null,
        val sourceText: String? = null,
        val rows: List<WorkoutRowPayload> = emptyList(),
    )

    // --- encode ---------------------------------------------------------------------------------

    fun exercise(exercise: UserExercise): String = with(exercise.exercise) {
        json.encodeToString(
            ExercisePayload.serializer(),
            ExercisePayload(
                id = id,
                name = name,
                primary = primary.map { it.name }.sorted(),
                secondary = secondary.map { it.name }.sorted(),
                equipment = equipment.name,
                pattern = pattern.name,
                isTimed = isTimed,
                unilateral = unilateral,
                notes = exercise.notes,
            ),
        )
    }

    fun ingredient(i: Ingredient): String = json.encodeToString(
        IngredientPayload.serializer(),
        IngredientPayload(
            name = i.name, brand = i.brand, barcode = i.barcode, basis = i.basis.name,
            pieceGrams = i.pieceGrams, servingGrams = i.servingGrams, servingLabel = i.servingLabel,
            kcal = i.kcal, proteinG = i.proteinG, carbsG = i.carbsG, sugarG = i.sugarG, fatG = i.fatG,
            satFatG = i.satFatG, fiberG = i.fiberG, saltG = i.saltG, sodiumG = i.sodiumG, source = i.source,
        ),
    )

    /** [ingredientShareId] names the library id of each item's ingredient (mine or theirs). */
    fun meal(template: MealTemplate, ingredientShareId: (Long) -> String): String = json.encodeToString(
        MealPayload.serializer(),
        MealPayload(
            name = template.name,
            defaultSlot = template.defaultSlot?.name,
            note = template.note,
            servings = template.servings,
            items = template.items.sortedBy { it.sortOrder }.map {
                MealItemPayload(ingredientShareId(it.ingredientId), it.quantity, it.unit.name, it.sortOrder)
            },
        ),
    )

    /** [exerciseShareId] names the library id of each own exercise; catalog ids travel as they are. */
    fun workout(workout: StrengthWorkout, exerciseShareId: (String) -> String): String = json.encodeToString(
        WorkoutPayload.serializer(),
        WorkoutPayload(
            name = workout.name,
            kind = workout.kind.name,
            notes = workout.notes,
            videoUrl = workout.videoUrl,
            videoTitle = workout.videoTitle,
            durationMin = workout.durationMin,
            sourceText = workout.sourceText,
            rows = workout.exercises.sortedBy { it.orderIndex }.map {
                WorkoutRowPayload(
                    exerciseId = it.exerciseId,
                    exerciseShareId = if (it.exerciseId.startsWith(Exercise.USER_EXERCISE_PREFIX)) {
                        exerciseShareId(it.exerciseId)
                    } else {
                        null
                    },
                    sets = it.sets, reps = it.reps, seconds = it.seconds, loadKg = it.loadKg,
                    isBodyweight = it.isBodyweight, restSec = it.restSec, note = it.note,
                    sourceLine = it.sourceLine,
                )
            },
        ),
    )

    // --- decode ---------------------------------------------------------------------------------

    fun decodeExercise(payload: String): ExercisePayload? = decode(ExercisePayload.serializer(), payload)

    fun decodeIngredient(payload: String): IngredientPayload? = decode(IngredientPayload.serializer(), payload)

    fun decodeMeal(payload: String): MealPayload? = decode(MealPayload.serializer(), payload)

    fun decodeWorkout(payload: String): WorkoutPayload? = decode(WorkoutPayload.serializer(), payload)

    /** The library ids an item needs on the device before it can be imported. */
    fun dependencies(item: SharedItem): List<String> = when (item.kind) {
        ShareKind.MEAL -> decodeMeal(item.payload)?.items?.map { it.ingredientShareId }.orEmpty().distinct()
        ShareKind.WORKOUT -> decodeWorkout(item.payload)?.rows?.mapNotNull { it.exerciseShareId }.orEmpty().distinct()
        ShareKind.EXERCISE, ShareKind.INGREDIENT -> emptyList()
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, payload: String): T? =
        runCatching { json.decodeFromString(serializer, payload) }.getOrNull()

    // --- payload → local model --------------------------------------------------------------------

    fun ExercisePayload.toUserExercise(nowMillis: Long, id: String = this.id): UserExercise = UserExercise(
        exercise = Exercise(
            id = id,
            name = name,
            primary = primary.mapNotNull { muscle(it) }.toSet(),
            secondary = secondary.mapNotNull { muscle(it) }.toSet(),
            equipment = enumOr(equipment, Equipment.BODYWEIGHT),
            pattern = enumOr(pattern, MovementPattern.OTHER),
            unilateral = unilateral,
            isTimed = isTimed,
            cue = notes.orEmpty(),
        ),
        notes = notes,
        createdAtMillis = nowMillis,
        updatedAtMillis = nowMillis,
    )

    fun IngredientPayload.toIngredient(nowMillis: Long, id: Long = 0L): Ingredient = Ingredient(
        id = id, name = name, brand = brand, barcode = barcode,
        basis = enumOr(basis, MeasureBasis.PER_100G),
        pieceGrams = pieceGrams, servingGrams = servingGrams, servingLabel = servingLabel,
        kcal = kcal, proteinG = proteinG, carbsG = carbsG, sugarG = sugarG, fatG = fatG,
        satFatG = satFatG, fiberG = fiberG, saltG = saltG, sodiumG = sodiumG,
        isFavorite = false, source = source, offProductJson = null, lastUsedAtMillis = null,
        useCount = 0, archived = false, createdAtMillis = nowMillis, updatedAtMillis = nowMillis,
    )

    /** Items whose ingredient [localIngredientId] cannot resolve are left out. */
    fun MealPayload.toTemplate(nowMillis: Long, localIngredientId: (String) -> Long?, id: Long = 0L): MealTemplate =
        MealTemplate(
            id = id,
            name = name,
            defaultSlot = defaultSlot?.let { enumOrNull<MealSlot>(it) },
            note = note,
            isFavorite = false,
            useCount = 0,
            lastUsedAtMillis = null,
            archived = false,
            items = items.mapNotNull { item ->
                localIngredientId(item.ingredientShareId)?.let {
                    MealTemplateItem(
                        id = 0L, templateId = id, ingredientId = it, quantity = item.quantity,
                        unit = enumOr(item.unit, QuantityUnit.G), sortOrder = item.sortOrder,
                    )
                }
            },
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            servings = servings,
        )

    fun WorkoutPayload.toWorkout(nowMillis: Long, id: Long = 0L): StrengthWorkout = StrengthWorkout(
        id = id,
        name = name,
        kind = enumOr(kind, StrengthWorkoutKind.FULL),
        notes = notes,
        exercises = rows.mapIndexed { index, row ->
            StrengthWorkoutExercise(
                id = 0L, workoutId = id, orderIndex = index, exerciseId = row.exerciseId, sets = row.sets,
                reps = row.reps, seconds = row.seconds, loadKg = row.loadKg, isBodyweight = row.isBodyweight,
                restSec = row.restSec, note = row.note, sourceLine = row.sourceLine,
            )
        },
        createdAtMillis = nowMillis,
        updatedAtMillis = nowMillis,
        videoUrl = videoUrl,
        videoTitle = videoTitle,
        durationMin = durationMin,
        sourceText = sourceText,
    )

    private fun muscle(name: String): MuscleGroup? = enumOrNull<MuscleGroup>(name)

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
        enumValues<E>().firstOrNull { it.name == name }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E = enumOrNull<E>(name) ?: fallback
}
