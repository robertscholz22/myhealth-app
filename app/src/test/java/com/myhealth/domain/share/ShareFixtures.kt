package com.myhealth.domain.share

import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.Ingredient
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

/** Invented content for the P27 tests: two authors, "uid-me" and "uid-anna". */
internal object ShareFixtures {
    const val ME = "uid-me"
    const val ANNA = "uid-anna"
    const val T = 1_800_000_000_000L

    fun exercise(id: String = "USER_BANDWALK0001", name: String = "Band walk") = UserExercise(
        exercise = Exercise(
            id = id, name = name, primary = setOf(MuscleGroup.GLUTES), secondary = emptySet(),
            equipment = Equipment.BAND, pattern = MovementPattern.OTHER, cue = "",
        ),
        notes = "Knees out",
        createdAtMillis = T,
        updatedAtMillis = T,
    )

    fun ingredient(id: Long = 1L, name: String = "Oats", barcode: String? = null) = Ingredient(
        id = id, name = name, brand = null, barcode = barcode, basis = MeasureBasis.PER_100G,
        pieceGrams = null, servingGrams = 40.0, servingLabel = null, kcal = 370.0, proteinG = 13.0,
        carbsG = 59.0, sugarG = 1.0, fatG = 7.0, satFatG = 1.2, fiberG = 10.0, saltG = 0.0, sodiumG = null,
        isFavorite = true, source = "OFF", offProductJson = "{\"raw\":true}", lastUsedAtMillis = T,
        useCount = 12, archived = false, createdAtMillis = T, updatedAtMillis = T,
    )

    fun template(ingredientIds: List<Long> = listOf(1L, 2L)) = MealTemplate(
        id = 5L, name = "Overnight oats", defaultSlot = null, note = "Soak overnight", isFavorite = true,
        useCount = 3, lastUsedAtMillis = T, archived = false,
        items = ingredientIds.mapIndexed { i, id ->
            MealTemplateItem(id = 10L + i, templateId = 5L, ingredientId = id, quantity = 50.0 + i, unit = QuantityUnit.G, sortOrder = i)
        },
        createdAtMillis = T, updatedAtMillis = T, servings = 2.0,
    )

    fun workout(exerciseIds: List<String> = listOf("PUSH_UP", "USER_BANDWALK0001")) = StrengthWorkout(
        id = 7L, name = "Glute video", kind = StrengthWorkoutKind.LOWER, notes = null,
        exercises = exerciseIds.mapIndexed { i, id ->
            StrengthWorkoutExercise(id = 20L + i, workoutId = 7L, orderIndex = i, exerciseId = id, sets = 3, seconds = 40)
        },
        createdAtMillis = T, updatedAtMillis = T, useInSuggestions = false,
        videoUrl = "https://www.youtube.com/watch?v=EXAMPLE0001", videoTitle = "Example glute workout", durationMin = 25,
    )

    fun item(
        shareId: String,
        kind: ShareKind,
        payload: String,
        owner: String = ANNA,
        author: String = "Anna",
        version: Long = 1L,
        name: String = shareId,
    ) = SharedItem(shareId, kind, owner, author, name, version, T, SharePayloads.SCHEMA, payload)

    fun ingredientItem(shareId: String, owner: String = ANNA, version: Long = 1L, barcode: String? = null, name: String = "Oats") =
        item(shareId, ShareKind.INGREDIENT, SharePayloads.ingredient(ingredient(name = name, barcode = barcode)), owner, version = version)

    fun mealItem(shareId: String, ingredientShareIds: List<String>, owner: String = ANNA, version: Long = 1L) =
        item(
            shareId, ShareKind.MEAL,
            SharePayloads.meal(template(ingredientShareIds.indices.map { it + 1L })) { ingredientShareIds[(it - 1).toInt()] },
            owner, version = version,
        )

    fun theirs(kind: ShareKind, localKey: String, shareId: String, version: Long = 1L, author: String = "Anna", authorId: String = ANNA) =
        ShareLink(kind, localKey, shareId, authorId, author, version, T)

    fun mine(kind: ShareKind, localKey: String, shareId: String, version: Long = 1L, sharedAt: Long = T) =
        ShareLink(kind, localKey, shareId, ME, "Me", version, sharedAt)
}
