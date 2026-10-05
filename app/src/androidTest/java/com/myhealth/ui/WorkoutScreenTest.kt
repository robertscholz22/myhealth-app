package com.myhealth.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.MainActivity
import com.myhealth.resources.*
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * PLAN P14.9: "Strength workouts" (More entry, P14.7) seeds the six built-in templates; opening
 * "Upper A" lets the athlete reorder its exercises, rename it and save, and the renamed workout can
 * then be attached to a `STRENGTH_UPPER` planned session through the planned-session editor
 * (P14.6/P14.7's `WorkoutPickerField`), which the Training board's session card then reflects.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutScreenTest {

    @get:Rule(order = 0)
    val clearState = ClearAppStateRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun seedProfile() {
        testGraph().seedProfileBlocking()
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
    }

    @Test
    fun workouts_createFromUpperA_reorder_save_attachToPlannedSession() {
        val activity = composeTestRule.activity
        val newName = "Upper A (edited)"

        // ---- More -> Strength workouts (seeds the six built-in templates) ----------------
        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.more_entry_workouts))
        composeTestRule.onNodeWithText(str(Res.string.more_entry_workouts)).performClick()
        composeTestRule.waitUntilTextExists("Core A")
        // Sorted by name, "Upper A" is the last card — below the fold since P19's checkbox row.
        composeTestRule.verticalScroller().performScrollToNode(hasText("Upper A"))

        // ---- Open "Upper A": move its first exercise down, rename, save ------------------
        composeTestRule.onNodeWithText("Upper A").performClick()
        composeTestRule.waitUntilTextExists("Barbell bench press")

        val moveDownDesc = str(Res.string.workout_edit_move_down)
        composeTestRule.onAllNodesWithContentDescription(moveDownDesc)[0].performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(str(Res.string.workout_edit_name_label))
            .performTextReplacement(newName)
        composeTestRule.waitForIdle()
        // "Save" sits below all six exercise rows — scroll the list to it rather than assume it
        // is already composed (same LazyColumn-virtualization idiom used throughout this suite).
        val saveLabel = str(Res.string.action_save)
        composeTestRule.verticalScroller().performScrollToNode(hasText(saveLabel))
        composeTestRule.onNodeWithText(saveLabel).performClick()

        // ---- Back on the Workouts list: the new name shows --------------------------------
        composeTestRule.waitUntilTextExists(newName)

        // ---- A planned STRENGTH_UPPER session for today, created through the graph --------
        val graph = testGraph()
        val today = LocalDate.now(graph.clock).toEpochDay()
        runBlocking {
            val now = graph.clock.millis()
            val session = PlannedSession(
                id = 0L,
                planId = null,
                day = today,
                startMinuteOfDay = null,
                sportType = SportType.STRENGTH,
                sessionType = SessionType.STRENGTH_UPPER,
                intensity = Intensity.MODERATE,
                targetDurationMin = 45,
                targetDistanceMeters = null,
                targetPaceSecPerKm = null,
                estimatedTrimp = null,
                description = null,
                rationale = null,
                status = PlannedStatus.PLANNED,
                locked = false,
                linkedActivityId = null,
                sourceSuggestionId = null,
                createdAtMillis = now,
                updatedAtMillis = now,
                workoutId = null,
            )
            (graph.planRepo.upsertSession(session) as Outcome.Ok)
        }
        // A sanity check that the rename actually persisted, before trusting the UI to find it.
        runBlocking { require(graph.strengthRepo.observeAll().first().any { it.name == newName }) }

        // ---- Training tab: edit the session, attach the workout, save ---------------------
        composeTestRule.onNodeWithText(str(Res.string.nav_training)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.training_this_block_title))
        // The session's day row is a LazyColumn item — not necessarily composed until scrolled
        // into view (same idiom as MuscleLoadCardTest / SettingsPersistenceTest.openSettings).
        val overflowDesc = str(Res.string.daydetail_overflow_more_actions_desc)
        composeTestRule.verticalScroller().performScrollToNode(hasContentDescription(overflowDesc))
        composeTestRule.onNodeWithContentDescription(overflowDesc).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.training_edit))
        composeTestRule.onNodeWithText(str(Res.string.training_edit)).performClick()

        composeTestRule.waitUntilTextExists(str(Res.string.session_edit_title))
        composeTestRule.onNodeWithText(str(Res.string.session_workout_label)).performClick()
        composeTestRule.waitUntilTextExists(newName)
        composeTestRule.onNodeWithText(newName).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.verticalScroller().performScrollToNode(hasText(saveLabel))
        composeTestRule.onNodeWithText(saveLabel).performClick()

        // ---- Back on Training: the session card shows the workout name --------------------
        composeTestRule.waitUntilTextExists(str(Res.string.session_workout_format, newName))
    }

    /** P19: the "Use in suggestions" checkbox on a workout card persists to the row. */
    @Test
    fun workouts_useInSuggestions_checkbox_persists() {
        val activity = composeTestRule.activity
        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.more_entry_workouts))
        composeTestRule.onNodeWithText(str(Res.string.more_entry_workouts)).performClick()
        // Sorted by name, "Core A" is the first card.
        composeTestRule.waitUntilTextExists("Core A")

        val label = str(Res.string.workouts_use_in_suggestions)
        composeTestRule.onAllNodesWithText(label)[0].assertIsOn().performClick()
        composeTestRule.waitForIdle()

        val repo = testGraph().strengthRepo
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repo.getByTemplateId("CORE_A")?.useInSuggestions == false }
        }
        composeTestRule.onAllNodesWithText(label)[0].assertIsOff()
    }
}
