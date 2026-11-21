package com.myhealth.domain.engine.strength.video

import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.model.StrengthWorkoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P26.4: import rows → video workout. */
class VideoWorkoutBuilderTest {

    private fun row(id: String, sets: Int = 3, reps: Int? = null, seconds: Int? = null, perSide: Boolean = false) =
        VideoRow(ExerciseCatalog.byId(id)!!, sets, reps, seconds, perSide, "$id line")

    @Test
    fun rows_get_exactly_one_of_reps_and_seconds() {
        val a = VideoWorkoutBuilder.row(row("PUSH_UP", reps = 12, seconds = 40), 0L, 0)
        assertEquals(40, a.seconds); assertNull(a.reps)
        val b = VideoWorkoutBuilder.row(row("PLANK"), 0L, 1)
        assertEquals(VideoWorkoutBuilder.DEFAULT_SECONDS, b.seconds); assertNull(b.reps)
        val c = VideoWorkoutBuilder.row(row("PUSH_UP"), 0L, 2)
        assertEquals(VideoWorkoutBuilder.DEFAULT_REPS, c.reps); assertNull(c.seconds)
    }

    @Test
    fun per_side_and_source_line_are_kept() {
        val r = VideoWorkoutBuilder.row(row("SIDE_PLANK", seconds = 30, perSide = true), 5L, 0)
        assertEquals(VideoWorkoutBuilder.PER_SIDE_NOTE, r.note)
        assertEquals("SIDE_PLANK line", r.sourceLine)
        assertEquals(5L, r.workoutId)
    }

    @Test
    fun build_sets_video_fields_and_normalises_the_link() {
        val w = VideoWorkoutBuilder.build(
            base = null, name = " Core 20 ", videoUrl = "https://youtu.be/AbCdEfGhIj0", videoTitle = " ",
            durationMin = 20, sourceText = "Plank 40s", rows = listOf(row("PLANK", seconds = 40)), kind = null, nowMillis = 7L,
        )
        assertEquals("Core 20", w.name)
        assertEquals("https://www.youtube.com/watch?v=AbCdEfGhIj0", w.videoUrl)
        assertNull(w.videoTitle)
        assertEquals(20, w.estimatedMinutes)
        assertEquals(7L, w.createdAtMillis)
        assertEquals(true, w.useInSuggestions)
        assertEquals(StrengthWorkoutKind.CORE, w.kind)
    }

    @Test
    fun rebuild_keeps_id_creation_time_and_suggestion_switch() {
        val first = VideoWorkoutBuilder.build(null, "A", "https://vimeo.com/1", null, 30, null, listOf(row("PUSH_UP")), null, 1L)
            .copy(id = 9L, useInSuggestions = false)
        val again = VideoWorkoutBuilder.build(first, "A", "https://vimeo.com/1", null, 30, null, listOf(row("PUSH_UP")), null, 5L)
        assertEquals(9L, again.id)
        assertEquals(1L, again.createdAtMillis)
        assertEquals(5L, again.updatedAtMillis)
        assertEquals(false, again.useInSuggestions)
    }

    @Test
    fun derived_kinds() {
        assertEquals(StrengthWorkoutKind.LOWER, VideoWorkoutBuilder.deriveKind(listOf(row("GLUTE_BRIDGE"), row("WALKING_LUNGE"), row("CALF_RAISE"))))
        assertEquals(StrengthWorkoutKind.UPPER, VideoWorkoutBuilder.deriveKind(listOf(row("PUSH_UP"), row("TRICEPS_DIP"), row("LATERAL_RAISE"))))
        assertEquals(StrengthWorkoutKind.CORE, VideoWorkoutBuilder.deriveKind(listOf(row("PLANK"), row("DEAD_BUG"), row("RUSSIAN_TWIST"))))
        assertEquals(StrengthWorkoutKind.FULL, VideoWorkoutBuilder.deriveKind(listOf(row("GOBLET_SQUAT"), row("PUSH_UP"), row("PLANK"))))
        assertEquals(StrengthWorkoutKind.MOBILITY_FULL, VideoWorkoutBuilder.deriveKind(listOf(row("MOB_CAT_COW"), row("MOB_PIGEON"), row("MOB_DOORWAY_PEC_STRETCH"))))
        assertEquals(StrengthWorkoutKind.MOBILITY_LOWER, VideoWorkoutBuilder.deriveKind(listOf(row("MOB_PIGEON"), row("MOB_COUCH_STRETCH"), row("MOB_WALL_CALF_STRETCH"))))
        assertEquals(StrengthWorkoutKind.FULL, VideoWorkoutBuilder.deriveKind(emptyList()))
    }

    @Test
    fun sets_weigh_the_kind() {
        val kind = VideoWorkoutBuilder.deriveKind(listOf(row("PUSH_UP", sets = 1), row("GLUTE_BRIDGE", sets = 6), row("WALKING_LUNGE", sets = 6)))
        assertEquals(StrengthWorkoutKind.LOWER, kind)
    }
}
