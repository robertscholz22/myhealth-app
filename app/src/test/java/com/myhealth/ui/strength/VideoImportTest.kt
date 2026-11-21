package com.myhealth.ui.strength

import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.engine.strength.video.ExerciseMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P26.4: the import screen's pure steps. Invented lists only. */
class VideoImportTest {

    private val library = ExerciseLibrary.CATALOG_ONLY
    private val text = "Push-ups 40s\nGlute bridge pulses 40s\nMountain climbers 30s\nRest 20s"

    @Test
    fun rows_carry_status_from_the_matcher() {
        val (lines, rows) = VideoImport.rows(text, emptySet(), library, emptyMap())
        assertEquals(4, lines.size)
        assertEquals(listOf(ImportRowStatus.MATCHED, ImportRowStatus.CHECK, ImportRowStatus.NOT_FOUND), rows.map { it.status })
        assertEquals("PUSH_UP", rows[0].chosenId)
        assertNull(rows[2].chosenId)
    }

    @Test
    fun choosing_by_hand_matches_and_is_learned() {
        val (_, rows) = VideoImport.rows(text, emptySet(), library, emptyMap())
        val chosen = VideoImport.choose(rows, rows[1].key, ExerciseCatalog.byId("HIP_THRUST")!!)
        assertEquals(ImportRowStatus.MATCHED, chosen[1].status)
        assertEquals(mapOf(rows[1].key to "HIP_THRUST"), VideoImport.learnedAliases(chosen))
    }

    @Test
    fun confirming_an_automatic_match_is_not_learned() {
        val (_, rows) = VideoImport.rows(text, emptySet(), library, emptyMap())
        val chosen = VideoImport.choose(rows, rows[0].key, ExerciseCatalog.byId("PUSH_UP")!!)
        assertTrue(VideoImport.learnedAliases(chosen).isEmpty())
    }

    @Test
    fun excluded_rows_are_not_saved_or_counted_as_unmatched() {
        val (_, rows) = VideoImport.rows(text, emptySet(), library, emptyMap())
        val excluded = VideoImport.setExcluded(rows, rows[2].key, true)
        val state = VideoImportUiState(rows = excluded)
        assertEquals(0, state.unmatchedCount)
        assertEquals(2, state.videoRows(library).size)
    }

    @Test
    fun hand_choices_survive_a_reparse() {
        val (_, rows) = VideoImport.rows(text, emptySet(), library, emptyMap())
        val chosen = VideoImport.choose(rows, rows[2].key, ExerciseCatalog.byId("PLANK")!!)
        val restIndex = 3
        val (_, again) = VideoImport.rows(text, setOf(restIndex), library, emptyMap(), current = chosen)
        assertEquals("PLANK", again.first { it.key == rows[2].key }.chosenId)
        assertEquals(4, again.size)
    }

    @Test
    fun recheck_keeps_saved_choices_by_source_line() {
        val previous = mapOf("Mountain climbers 30s" to "PLANK")
        val (_, rows) = VideoImport.rows(text, emptySet(), library, emptyMap(), previousBySourceLine = previous)
        val row = rows.first { it.sourceLine == "Mountain climbers 30s" }
        assertEquals("PLANK", row.chosenId)
        assertTrue(row.chosenByHand)
    }

    @Test
    fun learned_aliases_match_next_time() {
        val aliases = mapOf(ExerciseMatcher.key("Mountain climbers") to "PLANK")
        val (_, rows) = VideoImport.rows(text, emptySet(), library, aliases)
        assertEquals(ImportRowStatus.MATCHED, rows[2].status)
    }

    @Test
    fun amounts_can_be_changed() {
        val (_, rows) = VideoImport.rows(text, emptySet(), library, emptyMap())
        val changed = VideoImport.setAmounts(rows, rows[0].key, 0, 15, null)
        assertEquals(1, changed[0].sets)
        assertEquals(15, changed[0].reps)
        assertNull(changed[0].seconds)
    }

    @Test
    fun step_one_validation() {
        assertFalse(VideoImportUiState(url = "", name = "A", text = "Squats").canContinue)
        assertFalse(VideoImportUiState(url = "https://vimeo.com/1", name = " ", text = "Squats").canContinue)
        assertFalse(VideoImportUiState(url = "https://vimeo.com/1", name = "A", text = "Warm-up\nRest").canContinue)
        assertTrue(VideoImportUiState(url = "https://vimeo.com/1", name = "A", text = "Squats").canContinue)
    }
}
