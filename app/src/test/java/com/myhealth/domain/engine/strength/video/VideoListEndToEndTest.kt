package com.myhealth.domain.engine.strength.video

import com.myhealth.domain.engine.strength.ExerciseLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

/** P26.3: whole invented lists, as a video description might give them, through parser and matcher. */
class VideoListEndToEndTest {

    private val matcher = ExerciseMatcher(ExerciseLibrary.CATALOG_ONLY)

    @Test
    fun english_chapter_list() {
        val text = """
            0:00 Intro
            0:40 Warm up
            3:00 Squat 40s
            3:55 Push-ups 40s
            4:50 Glute bridges 40s
            5:45 Rest 20s
            6:05 Plank 40s
            7:00 Side plank 30s each side
            8:10 Mountain climbers 40s
            9:00 Cool down
        """.trimIndent()
        val lines = ExerciseListParser.parse(text)
        val entries = ExerciseListParser.entries(lines)
        assertEquals(listOf("Squat", "Push-ups", "Glute bridges", "Plank", "Side plank", "Mountain climbers"), entries.map { it.name })
        assertEquals(listOf(40, 40, 40, 40, 30, 40), entries.map { it.seconds })
        assertEquals(true, entries[4].perSide)
        val statuses = entries.map { matcher.match(it.name).status }
        assertEquals(
            List(6) { MatchStatus.MATCHED },
            statuses,
        )
        // P26.9: the home-workout moves made the bodyweight squat and mountain climbers matchable.
        assertEquals(
            listOf("AIR_SQUAT", "PUSH_UP", "GLUTE_BRIDGE", "PLANK", "SIDE_PLANK", "MOUNTAIN_CLIMBER"),
            entries.map { matcher.match(it.name).best?.id },
        )
        assertEquals(4, lines.count { it.kind == LineKind.SKIP })
    }

    @Test
    fun german_circuit_with_rounds() {
        val text = """
            Aufwärmen 5 min
            Zirkel – 3 Runden:
            1. Kniebeugen 45 Sek
            2. Liegestütze 45 Sek
            3. Beckenheben 45 Sek
            4. Unterarmstütz 45 Sek
            Pause 1 min
            Dehnen
        """.trimIndent()
        val entries = ExerciseListParser.entries(ExerciseListParser.parse(text))
        assertEquals(listOf("Kniebeugen", "Liegestütze", "Beckenheben", "Unterarmstütz"), entries.map { it.name })
        assertEquals(listOf(3, 3, 3, 3), entries.map { it.sets })
        assertEquals(listOf(45, 45, 45, 45), entries.map { it.seconds })
        assertEquals(listOf("AIR_SQUAT", "PUSH_UP", "GLUTE_BRIDGE", "PLANK"), entries.map { matcher.match(it.name).best?.id })
    }
}
