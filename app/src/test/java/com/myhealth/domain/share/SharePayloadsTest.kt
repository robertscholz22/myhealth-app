package com.myhealth.domain.share

import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.share.SharePayloads.toIngredient
import com.myhealth.domain.share.SharePayloads.toTemplate
import com.myhealth.domain.share.SharePayloads.toUserExercise
import com.myhealth.domain.share.SharePayloads.toWorkout
import com.myhealth.domain.share.ShareFixtures as F
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePayloadsTest {

    @Test
    fun sh01_exercise_round_trips_and_keeps_its_random_id() {
        val back = SharePayloads.decodeExercise(SharePayloads.exercise(F.exercise()))!!.toUserExercise(99L)
        assertEquals("USER_BANDWALK0001", back.exercise.id)
        assertEquals("Band walk", back.exercise.name)
        assertEquals(setOf(MuscleGroup.GLUTES), back.exercise.primary)
        assertEquals(Equipment.BAND, back.exercise.equipment)
        assertEquals("Knees out", back.notes)
        assertEquals(99L, back.createdAtMillis)
    }

    @Test
    fun sh02_ingredient_travels_without_personal_state_or_the_raw_off_payload() {
        val json = SharePayloads.ingredient(F.ingredient())
        assertFalse(json.contains("raw"))
        assertFalse(json.contains("useCount"))
        assertFalse(json.contains("isFavorite"))
        assertFalse(json.contains("lastUsed"))
        val back = SharePayloads.decodeIngredient(json)!!.toIngredient(5L)
        assertEquals(370.0, back.kcal, 0.0)
        assertEquals(13.0, back.proteinG!!, 0.0)
        assertEquals(40.0, back.servingGrams!!, 0.0)
        assertEquals(MeasureBasis.PER_100G, back.basis)
        assertFalse(back.isFavorite)
        assertEquals(0, back.useCount)
        assertNull(back.offProductJson)
        assertEquals("OFF", back.source)
    }

    @Test
    fun sh03_meal_names_ingredients_by_share_id_and_resolves_them_on_import() {
        val json = SharePayloads.meal(F.template()) { if (it == 1L) "s-oats" else "s-milk" }
        val payload = SharePayloads.decodeMeal(json)!!
        assertEquals(listOf("s-oats", "s-milk"), payload.items.map { it.ingredientShareId })
        assertFalse(json.contains("useCount"))
        val back = payload.toTemplate(1L, { mapOf("s-oats" to 31L, "s-milk" to 32L)[it] })
        assertEquals(listOf(31L, 32L), back.items.map { it.ingredientId })
        assertEquals(2.0, back.servings, 0.0)
        assertEquals("Soak overnight", back.note)
        assertEquals(QuantityUnit.G, back.items.first().unit)
        assertFalse(back.isFavorite)
    }

    @Test
    fun sh04_meal_item_with_an_unresolvable_ingredient_is_left_out() {
        val json = SharePayloads.meal(F.template()) { if (it == 1L) "s-oats" else "s-milk" }
        val back = SharePayloads.decodeMeal(json)!!.toTemplate(1L, { if (it == "s-oats") 31L else null })
        assertEquals(listOf(31L), back.items.map { it.ingredientId })
    }

    @Test
    fun sh05_workout_keeps_catalog_ids_and_names_own_exercises_by_share_id() {
        val json = SharePayloads.workout(F.workout()) { "s-$it" }
        val payload = SharePayloads.decodeWorkout(json)!!
        assertEquals(listOf(null, "s-USER_BANDWALK0001"), payload.rows.map { it.exerciseShareId })
        val back = payload.toWorkout(3L)
        assertEquals(listOf("PUSH_UP", "USER_BANDWALK0001"), back.exercises.map { it.exerciseId })
        assertEquals(StrengthWorkoutKind.LOWER, back.kind)
        assertEquals("https://www.youtube.com/watch?v=EXAMPLE0001", back.videoUrl)
        assertEquals(25, back.durationMin)
        assertEquals(40, back.exercises.first().seconds)
        // "Use in suggestions" is personal: an imported workout starts with the default.
        assertTrue(back.useInSuggestions)
    }

    @Test
    fun sh06_dependencies_list_what_must_be_on_the_device_first() {
        val meal = F.mealItem("m", listOf("a", "b", "a"))
        assertEquals(listOf("a", "b"), SharePayloads.dependencies(meal))
        val workout = F.item("w", ShareKind.WORKOUT, SharePayloads.workout(F.workout()) { "s-ex" })
        assertEquals(listOf("s-ex"), SharePayloads.dependencies(workout))
        assertEquals(emptyList<String>(), SharePayloads.dependencies(F.ingredientItem("i")))
    }

    @Test
    fun sh07_newer_payloads_still_decode_unknown_keys_and_enum_names() {
        val json = """{"id":"USER_X","name":"Future move","primary":["GLUTES","NEW_MUSCLE"],"equipment":"HOVERBOARD","pattern":"TELEPORT","extra":1}"""
        val back = SharePayloads.decodeExercise(json)!!.toUserExercise(1L)
        assertEquals(setOf(MuscleGroup.GLUTES), back.exercise.primary)
        assertEquals(Equipment.BODYWEIGHT, back.exercise.equipment)
        assertEquals(MovementPattern.OTHER, back.exercise.pattern)
    }

    @Test
    fun sh08_garbage_decodes_to_null_and_has_no_dependencies() {
        assertNull(SharePayloads.decodeMeal("not json"))
        assertEquals(emptyList<String>(), SharePayloads.dependencies(F.item("m", ShareKind.MEAL, "{")))
    }
}
