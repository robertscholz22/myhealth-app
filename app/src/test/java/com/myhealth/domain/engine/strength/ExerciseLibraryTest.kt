package com.myhealth.domain.engine.strength

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.entity.UserExerciseEntity
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.model.UserExercise
import org.junit.After
import org.junit.Test

/** P26.1: the catalog plus the owner's exercises, and the registry every lookup goes through. */
class ExerciseLibraryTest {

    @After
    fun tearDown() = ExerciseRegistry.reset()

    private fun own(id: String, name: String, archived: Boolean = false, pattern: MovementPattern = MovementPattern.OTHER) =
        UserExercise(
            exercise = Exercise(
                id = id, name = name, primary = setOf(MuscleGroup.GLUTES), secondary = setOf(MuscleGroup.ADDUCTORS),
                equipment = Equipment.BAND, pattern = pattern, cue = "",
            ),
            archived = archived,
            createdAtMillis = 1, updatedAtMillis = 1,
        )

    @Test
    fun catalog_only_by_default_and_identical_to_the_catalog() {
        val library = ExerciseRegistry.library
        assertThat(library.all).isEqualTo(ExerciseCatalog.ALL)
        assertThat(ExerciseRegistry.byId("PUSH_UP")).isEqualTo(ExerciseCatalog.byId("PUSH_UP"))
        assertThat(ExerciseRegistry.byId("USER_NOPE")).isNull()
        assertThat(library.search("squat")).isEqualTo(ExerciseCatalog.search("squat"))
        assertThat(library.of(ExerciseKind.MOBILITY)).isEqualTo(ExerciseCatalog.mobility)
    }

    @Test
    fun own_exercises_follow_the_catalog_and_archived_ones_only_resolve_by_id() {
        val library = ExerciseLibrary(
            listOf(own("USER_B", "Fire hydrant"), own("USER_A", "Band walk"), own("USER_C", "Old move", archived = true)),
        )
        assertThat(library.all.takeLast(2).map { it.name }).containsExactly("Band walk", "Fire hydrant").inOrder()
        assertThat(library.all.map { it.id }).doesNotContain("USER_C")
        assertThat(library.byId("USER_C")?.name).isEqualTo("Old move")
        assertThat(library.search("band").map { it.id }).contains("USER_A")
        assertThat(library.search("glutes").map { it.id }).containsAtLeast("USER_A", "USER_B")
        assertThat(library.filter(muscle = MuscleGroup.ADDUCTORS).map { it.id }).contains("USER_B")
        assertThat(library.of(ExerciseKind.STRENGTH).map { it.id }).contains("USER_A")
        assertThat(library.of(ExerciseKind.MOBILITY).map { it.id }).doesNotContain("USER_A")
    }

    @Test
    fun the_registry_serves_installed_exercises_to_the_muscle_load_engine() {
        val workout = StrengthWorkout(
            id = 1, name = "Video", kind = StrengthWorkoutKind.CUSTOM, createdAtMillis = 0, updatedAtMillis = 0,
            exercises = listOf(StrengthWorkoutExercise(0, 1, 0, "USER_A", sets = 2, seconds = 40)),
        )
        ExerciseRegistry.install(ExerciseLibrary(listOf(own("USER_A", "Band walk"))))
        val shares = checkNotNull(MuscleLoadEngine.workoutShares(workout))
        assertThat(shares.keys).containsExactly(MuscleGroup.GLUTES, MuscleGroup.ADDUCTORS)
        assertThat(shares.getValue(MuscleGroup.GLUTES)).isGreaterThan(shares.getValue(MuscleGroup.ADDUCTORS))
        assertThat(ExerciseAnimations.clipOrStanding("USER_A")).isEqualTo(ExerciseAnimations.defaultFor(MovementPattern.OTHER))
    }

    @Test
    fun a_video_workout_takes_its_duration_from_the_video() {
        val base = StrengthWorkout(
            id = 1, name = "W", kind = StrengthWorkoutKind.FULL, createdAtMillis = 0, updatedAtMillis = 0,
            exercises = listOf(StrengthWorkoutExercise(0, 1, 0, "PUSH_UP", sets = 3, reps = 10)),
        )
        assertThat(base.isVideo).isFalse()
        val video = base.copy(videoUrl = "https://youtu.be/example", durationMin = 32)
        assertThat(video.isVideo).isTrue()
        assertThat(video.estimatedMinutes).isEqualTo(32)
        assertThat(base.estimatedMinutes).isNotEqualTo(32)
    }

    @Test
    fun the_entity_round_trips_and_decodes_leniently() {
        val exercise = own("USER_A", "Band walk", pattern = MovementPattern.LUNGE)
        assertThat(exercise.toEntity().toDomain()).isEqualTo(exercise)
        assertThat(exercise.toEntity().primaryMuscles).isEqualTo("GLUTES")

        val odd = UserExerciseEntity(
            id = "USER_X", name = "Odd", primaryMuscles = "GLUTES,NECK", secondaryMuscles = "GLUTES,CALVES",
            equipment = "TRAMPOLINE", pattern = "SPIN", createdAtMillis = 1, updatedAtMillis = 1,
        ).toDomain().exercise
        assertThat(odd.primary).containsExactly(MuscleGroup.GLUTES)
        assertThat(odd.secondary).containsExactly(MuscleGroup.CALVES)
        assertThat(odd.equipment).isEqualTo(Equipment.BODYWEIGHT)
        assertThat(odd.pattern).isEqualTo(MovementPattern.OTHER)
        assertThat(odd.isCustom).isTrue()
    }
}

class UserExerciseIdsTest {
    @Test
    fun ids_are_prefixed_twelve_characters_and_distinct() {
        val ids = (1..200).map { UserExerciseIds.next(kotlin.random.Random(it)) }
        assertThat(ids.toSet()).hasSize(200)
        ids.forEach {
            assertThat(it).startsWith("USER_")
            assertThat(it).hasLength(17)
            assertThat(it).doesNotContainMatch("[01IO]")
        }
    }
}
