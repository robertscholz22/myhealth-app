package com.myhealth.domain.engine.strength.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P26.3: pasted list → lines. All lists here are invented. */
class ExerciseListParserTest {

    private fun exercises(text: String) = ExerciseListParser.parse(text).filter { it.kind == LineKind.EXERCISE }

    @Test
    fun one_move_per_line() {
        val lines = exercises("Squats\nLunges\nPush-ups")
        assertEquals(listOf("Squats", "Lunges", "Push-ups"), lines.map { it.name })
    }

    @Test
    fun single_line_split_on_commas_bullets_and_pipes() {
        assertEquals(listOf("Squats", "Lunges", "Plank"), exercises("Squats, Lunges, Plank").map { it.name })
        assertEquals(listOf("Squats", "Lunges", "Plank"), exercises("Squats • Lunges • Plank").map { it.name })
        assertEquals(listOf("Squats", "Lunges", "Plank"), exercises("Squats | Lunges | Plank").map { it.name })
    }

    @Test
    fun bullets_and_numbering_are_stripped() {
        val lines = exercises("- Squats\n* Lunges\n• Plank\n1. Burpees\n2) Mountain climbers\n#3 Crunches")
        assertEquals(listOf("Squats", "Lunges", "Plank", "Burpees", "Mountain climbers", "Crunches"), lines.map { it.name })
    }

    @Test
    fun chapter_timestamps_are_stripped() {
        val lines = exercises("0:00 Intro\n0:45 Jumping jacks\n1:30 - Squats\n12:05 | Glute bridge\n1:02:10 Plank")
        assertEquals(listOf("Jumping jacks", "Squats", "Glute bridge", "Plank"), lines.map { it.name })
    }

    @Test
    fun trailing_timestamp_is_stripped() {
        assertEquals("Squats", exercises("Squats - 3:20").single().name)
    }

    @Test
    fun emoji_are_stripped() {
        assertEquals(listOf("Squats", "Plank"), exercises("🔥 Squats 🔥\n💪 Plank").map { it.name })
    }

    @Test
    fun seconds_in_english_and_german() {
        assertEquals(40, exercises("Squats 40s").single().seconds)
        assertEquals(45, exercises("Plank 45 sec").single().seconds)
        assertEquals(30, exercises("Unterarmstütz 30 Sek.").single().seconds)
        assertEquals(20, exercises("Hollow hold (20 seconds)").single().seconds)
        assertEquals("Hollow hold", exercises("Hollow hold (20 seconds)").single().name)
    }

    @Test
    fun minutes_become_seconds() {
        assertEquals(60, exercises("Plank 1 min").single().seconds)
        assertEquals(90, exercises("Wall sit 1,5 Minuten").single().seconds)
    }

    @Test
    fun reps_in_english_and_german() {
        assertEquals(12, exercises("Squats x12").single().reps)
        assertEquals(15, exercises("Lunges 15 reps").single().reps)
        assertEquals(10, exercises("Liegestütze 10 Wdh").single().reps)
        assertEquals(8, exercises("Push-ups – 8 Wiederholungen").single().reps)
    }

    @Test
    fun sets_times_reps_and_sets_times_seconds() {
        val a = exercises("Squats 3 x 12").single()
        assertEquals(3, a.sets); assertEquals(12, a.reps); assertEquals("Squats", a.name)
        val b = exercises("Plank 3×40s").single()
        assertEquals(3, b.sets); assertEquals(40, b.seconds); assertNull(b.reps)
    }

    @Test
    fun rounds_words_and_bare_multiplier() {
        assertEquals(3, exercises("Burpees 3 rounds").single().sets)
        assertEquals(4, exercises("Kniebeugen 4 Runden").single().sets)
        assertEquals(2, exercises("Lunges 2x").single().sets)
        val c = exercises("Squats 3 sets of 12").single()
        assertEquals(3, c.sets); assertEquals(12, c.reps); assertEquals("Squats", c.name)
        assertEquals(3, exercises("Rudern 3 Sätze").single().sets)
    }

    @Test
    fun per_side_variants() {
        listOf(
            "Side plank 30s each side", "Lunges per leg", "Side plank (L/R)", "Clamshells pro Seite",
            "Fire hydrant left and right", "Step-ups (each)", "Side plank 20s/side", "Ausfallschritt je Bein",
        ).forEach { text ->
            val line = exercises(text).single()
            assertTrue(text, line.perSide)
            assertFalse(text, line.name.contains("side", ignoreCase = true) && text.startsWith("Lunges"))
        }
        assertEquals("Side plank", exercises("Side plank 30s each side").single().name)
        assertEquals("Clamshells", exercises("Clamshells pro Seite").single().name)
    }

    @Test
    fun warm_up_rest_and_intro_lines_are_skipped() {
        val lines = ExerciseListParser.parse(
            "Warm-up\nIntro\nSquats\nRest 30s\nPause\nAufwärmen\nCool down\nTrinkpause 1 min\nOutro",
        )
        assertEquals(listOf("Squats"), lines.filter { it.kind == LineKind.EXERCISE }.map { it.name })
        assertEquals(8, lines.count { it.kind == LineKind.SKIP })
    }

    @Test
    fun links_hashtags_and_long_sentences_are_skipped() {
        val lines = ExerciseListParser.parse(
            "Squats\nhttps://example.com/my-plan\n#homeworkout\nThanks for training with me today, see you next week in the next video!",
        )
        assertEquals(1, lines.count { it.kind == LineKind.EXERCISE })
        assertEquals(3, lines.count { it.kind == LineKind.SKIP })
    }

    @Test
    fun a_label_before_a_colon_is_peeled_off() {
        val lines = ExerciseListParser.parse("Warm-up: Jumping jacks\nRound 1: Squats")
        assertEquals(listOf("Jumping jacks", "Squats"), lines.filter { it.kind == LineKind.EXERCISE }.map { it.name })
        assertEquals(1, lines.count { it.kind == LineKind.HEADER })
    }

    @Test
    fun headers_are_recognised() {
        val lines = ExerciseListParser.parse("Round 1\nSquats\nBlock B\nPlank\nRunde 2\nCircuit\nTeil 3")
        assertEquals(5, lines.count { it.kind == LineKind.HEADER })
    }

    @Test
    fun a_repeated_move_across_rounds_becomes_one_entry_with_sets() {
        val lines = ExerciseListParser.parse("Round 1\nSquats 40s\nPush-ups 40s\nRound 2\nSquats 40s\nPush ups 40s")
        val entries = ExerciseListParser.entries(lines)
        assertEquals(2, entries.size)
        assertEquals("Squats", entries[0].name)
        assertEquals(2, entries[0].sets)
        assertEquals(40, entries[0].seconds)
        assertEquals(2, entries[1].sets)
        assertEquals(listOf(1, 4), entries[0].lineIndexes)
    }

    @Test
    fun header_with_a_count_multiplies_its_block() {
        val lines = ExerciseListParser.parse("Circuit 1 - 3 rounds\nSquats\nLunges\nCircuit 2 - 2 rounds\nPlank 30s")
        val entries = ExerciseListParser.entries(lines)
        assertEquals(listOf(3, 3, 2), entries.map { it.sets })
    }

    @Test
    fun repeat_line_after_a_block_multiplies_that_block() {
        val lines = ExerciseListParser.parse("Squats 45s\nBurpees 45s\nRepeat 3 times\nPlank 60s")
        val entries = ExerciseListParser.entries(lines)
        assertEquals(listOf(3, 3, 1), entries.map { it.sets })
    }

    @Test
    fun german_repeat_line_before_a_block() {
        val lines = ExerciseListParser.parse("3 Runden:\nKniebeugen 40 Sek\nLiegestütze 40 Sek")
        val entries = ExerciseListParser.entries(lines)
        assertEquals(listOf(3, 3), entries.map { it.sets })
    }

    @Test
    fun line_sets_times_block_rounds() {
        val lines = ExerciseListParser.parse("Round 1 x2\nSquats 3x10")
        assertEquals(6, ExerciseListParser.entries(lines).single().sets)
    }

    @Test
    fun switched_on_skip_lines_become_entries() {
        val lines = ExerciseListParser.parse("Squats\nStretching")
        assertEquals(1, ExerciseListParser.entries(lines).size)
        val stretch = lines.indexOfFirst { it.kind == LineKind.SKIP }
        assertEquals(2, ExerciseListParser.entries(lines, setOf(stretch)).size)
    }

    @Test
    fun blank_and_number_only_lines_are_dropped() {
        val lines = ExerciseListParser.parse("\n\n0:00\n   \nSquats\n12:30\n")
        assertEquals(listOf("Squats"), lines.map { it.name })
    }

    @Test
    fun name_keeps_the_original_spelling() {
        assertEquals("Booty Band Walk", exercises("3. Booty Band Walk – 45s").single().name)
    }

    @Test
    fun raw_line_is_kept_for_reimport() {
        val line = exercises("  1. Squats 40s ").single()
        assertEquals("1. Squats 40s", line.raw)
    }
}
