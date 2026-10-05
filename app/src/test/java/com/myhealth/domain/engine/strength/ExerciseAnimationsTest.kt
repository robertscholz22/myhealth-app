package com.myhealth.domain.engine.strength

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.myhealth.domain.model.MovementPattern
import org.junit.Test

/** P18.1's `an01` / `an09`: the per-exercise table and what happens off the end of it. */
class ExerciseAnimationsTest {

    @Test
    fun an01_every_shipped_exercise_has_a_clip() {
        // The catalog is 54 lifts + 33 P17 mobility drills; the table must cover all of them.
        assertThat(ExerciseCatalog.ALL).hasSize(87)
        assertThat(ExerciseCatalog.strength).hasSize(54)
        assertThat(ExerciseCatalog.mobility).hasSize(33)

        ExerciseCatalog.ALL.forEach { exercise ->
            val clip = ExerciseAnimations.clipFor(exercise.id)
            assertWithMessage("no clip for ${exercise.id}").that(clip).isNotNull()
            // The clip must be one the object actually publishes, not an orphan.
            assertThat(AnimationClips.ALL).contains(clip)
            // …and the typed overload must agree with the id lookup.
            assertThat(ExerciseAnimations.clipFor(exercise)).isEqualTo(clip)
        }

        // A few spot checks that the table says what it means.
        assertThat(ExerciseAnimations.clipFor("BARBELL_BACK_SQUAT")).isEqualTo(AnimationClips.SQUAT)
        assertThat(ExerciseAnimations.clipFor("PLANK")).isEqualTo(AnimationClips.PLANK)
        assertThat(ExerciseAnimations.clipFor("MOB_PIGEON")).isEqualTo(AnimationClips.PIGEON)
        assertThat(ExerciseAnimations.clipFor("MOB_FOAM_ROLL_LATS")).isEqualTo(AnimationClips.FOAM_ROLL_SIDE)
        assertThat(ExerciseAnimations.clipFor("GLUTE_BRIDGE")).isEqualTo(AnimationClips.HIP_THRUST)
        assertThat(ExerciseAnimations.clipFor("MOB_SHOULDER_CARS")).isEqualTo(AnimationClips.SHOULDER_CARS)

        // Every clip that exists is used by something — an unused one is a drawing nobody sees.
        val used = ExerciseCatalog.ALL.mapNotNull { ExerciseAnimations.clipFor(it.id) }.toSet()
        val unused = AnimationClips.ALL.filter { it != AnimationClips.STANDING && it !in used }
        assertWithMessage("clips nothing plays: ${unused.map { it.id }}").that(unused).isEmpty()
    }

    @Test
    fun an09_pattern_fallback_for_unknown_id_is_static_standing() {
        // An id nothing knows has no movement pattern either, so there is nothing to fall back on.
        assertThat(ExerciseAnimations.clipFor("NOT_A_REAL_ID")).isNull()

        val fallback = ExerciseAnimations.clipOrStanding("NOT_A_REAL_ID")
        assertThat(fallback).isEqualTo(AnimationClips.STANDING)
        // "Static" means it really does not move: identical keyframes, upright, unscaled.
        assertThat(fallback.keyframes.map { it.pose }.distinct()).hasSize(1)
        assertThat(fallback.keyframes.size).isAtLeast(1)
        val pose = fallback.keyframes.first().pose
        assertThat(pose.rootAngle).isEqualTo(0f)
        assertThat(pose.rootScale).isEqualTo(1f)
        assertThat(pose.angles.values.all { kotlin.math.abs(it) <= 6f }).isTrue()

        // A *known* exercise still resolves through the table rather than the fallback.
        assertThat(ExerciseAnimations.clipOrStanding("PUSH_UP")).isEqualTo(AnimationClips.PUSH_UP)

        // The per-pattern archetype exists for every pattern — that is what a custom exercise gets.
        MovementPattern.entries.forEach { pattern ->
            val clip = ExerciseAnimations.defaultFor(pattern)
            assertWithMessage("$pattern").that(AnimationClips.ALL).contains(clip)
        }
        assertThat(ExerciseAnimations.defaultFor(MovementPattern.SQUAT)).isEqualTo(AnimationClips.SQUAT)
        assertThat(ExerciseAnimations.defaultFor(MovementPattern.MOBILITY))
            .isEqualTo(AnimationClips.CAT_COW)
    }
}
