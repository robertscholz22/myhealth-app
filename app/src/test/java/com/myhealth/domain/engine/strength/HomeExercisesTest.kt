package com.myhealth.domain.engine.strength

import com.myhealth.domain.engine.strength.video.ExerciseMatcher
import com.myhealth.domain.engine.strength.video.MatchStatus
import com.myhealth.domain.model.Equipment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P26.9: the home-workout moves — matchable, animated, bodyweight, and invisible to substitution. */
class HomeExercisesTest {

    private val home = ExerciseCatalog.ALL.filter { it.id in ExerciseCatalog.HOME_IDS }
    private val matcher = ExerciseMatcher(ExerciseLibrary.CATALOG_ONLY)

    @Test
    fun appended_after_everything_else_so_no_list_position_moves() {
        assertEquals(23, home.size)
        assertEquals(87, ExerciseCatalog.ALL.indexOf(home.first()))
        assertEquals(home, ExerciseCatalog.ALL.takeLast(home.size))
        assertTrue(home.none { it.isMobility })
    }

    @Test
    fun all_bodyweight_with_a_cue_and_a_clip() {
        home.forEach { e ->
            assertEquals(e.id, Equipment.BODYWEIGHT, e.equipment)
            assertTrue(e.id, e.cue.isNotBlank() && e.primary.isNotEmpty())
            assertTrue(e.id, ExerciseAnimations.clipFor(e.id) in AnimationClips.ALL)
        }
    }

    @Test
    fun video_names_match_in_english_and_german() {
        val expected = mapOf(
            "Burpees" to "BURPEE",
            "Jumping Jacks" to "JUMPING_JACK",
            "Hampelmann" to "JUMPING_JACK",
            "Mountain climbers" to "MOUNTAIN_CLIMBER",
            "Bergsteiger" to "MOUNTAIN_CLIMBER",
            "Donkey kicks" to "DONKEY_KICK",
            "Fire hydrants" to "FIRE_HYDRANT",
            "Clamshells" to "CLAMSHELL",
            "High knees" to "HIGH_KNEES",
            "Squats" to "AIR_SQUAT",
            "Kniebeugen" to "AIR_SQUAT",
            "Jump squats" to "SQUAT_JUMP",
            "Sumo squats" to "SUMO_SQUAT",
            "Lateral lunges" to "SIDE_LUNGE",
            "Curtsy lunges" to "CURTSY_LUNGE",
            "Knee push-ups" to "KNEE_PUSH_UP",
            "Diamond push ups" to "DIAMOND_PUSH_UP",
            "Pike push-ups" to "PIKE_PUSH_UP",
            "Shoulder taps" to "PLANK_SHOULDER_TAP",
            "Crunches" to "CRUNCH",
            "Bicycle crunches" to "BICYCLE_CRUNCH",
            "Leg raises" to "LYING_LEG_RAISE",
            "Flutter kicks" to "FLUTTER_KICK",
            "Superman" to "SUPERMAN",
            "Skaters" to "SKATER_JUMP",
            "Single leg glute bridge" to "SINGLE_LEG_GLUTE_BRIDGE",
        )
        expected.forEach { (name, id) ->
            val r = matcher.match(name)
            assertEquals(name, id, r.best?.id)
            assertEquals(name, MatchStatus.MATCHED, r.status)
        }
        // The old exact matches did not move to a new entry.
        assertEquals("PUSH_UP", matcher.match("Push-ups").best?.id)
        assertEquals("GLUTE_BRIDGE", matcher.match("Glute bridges").best?.id)
        assertEquals("GOBLET_SQUAT", matcher.match("Goblet squat").best?.id)
        assertEquals("HANGING_LEG_RAISE", matcher.match("Hanging leg raises").best?.id)
    }

    @Test
    fun never_offered_as_a_substitute() {
        val homeOnly = setOf(Equipment.BODYWEIGHT)
        ExerciseCatalog.ALL.filter { it.id !in ExerciseCatalog.HOME_IDS }.forEach { e ->
            val sub = ExerciseSubstitution.substitute(e, homeOnly)
            assertTrue("${e.id} -> ${sub?.id}", sub == null || sub.id !in ExerciseCatalog.HOME_IDS)
        }
    }
}
