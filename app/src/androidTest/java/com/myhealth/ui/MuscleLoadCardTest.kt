package com.myhealth.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.MainActivity
import com.myhealth.resources.*
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * PLAN P14.9: the "Muscle load" card on Load & Recovery (More entry, P14.8) shows after a hard run
 * — [com.myhealth.domain.engine.strength.MuscleLoadEngine] deposits the run's TRIMP mostly onto the
 * legs (`MuscleDistribution.RUN`), so a single 220-AU run leaves the legs anything but fresh while
 * the arms stay untouched, which is exactly [com.myhealth.ui.load.MuscleLoadHint.UPPER_DAY_FITS].
 */
@RunWith(AndroidJUnit4::class)
class MuscleLoadCardTest {

    @get:Rule(order = 0)
    val clearState = ClearAppStateRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun seedProfileAndHardRun() {
        val graph = testGraph()
        graph.seedProfileBlocking()

        runBlocking {
            val today = LocalDate.now(graph.clock).toEpochDay()
            val runDay = today - 1
            val startAtMillis = runDay * 86_400_000L + 7 * 3_600_000L

            val session = ActivitySession(
                id = 0L,
                startAtMillis = startAtMillis,
                endAtMillis = startAtMillis + 3_600_000L,
                day = runDay,
                sportType = SportType.RUN_OUTDOOR,
                sportGroup = SportGroup.RUN,
                title = "Hard run",
                durationSec = 3_600,
                elapsedSec = 3_600,
                distanceMeters = 12_000.0,
                activeEnergyKcal = null,
                totalEnergyKcal = null,
                avgHr = 165,
                maxHr = 178,
                avgSpeedMps = null,
                maxSpeedMps = null,
                avgCadenceSpm = null,
                elevationGainM = null,
                trimp = 220.0,
                loadMethod = null,
                rpe = null,
                note = null,
                primarySource = ActivitySource.MANUAL,
                mergedSources = listOf(ActivitySource.MANUAL),
                dedupeBucket = "${SportGroup.RUN}|${startAtMillis / 300_000L}",
                userEditedFields = emptyList(),
                hasStreams = false,
                streams = null,
                laps = emptyList(),
                createdAtMillis = graph.clock.millis(),
                updatedAtMillis = graph.clock.millis(),
            )
            (graph.activityRepo.upsert(session) as Outcome.Ok)
        }

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
    }

    @Test
    fun loadScreen_showsMuscleLoadCard_afterAHardRun() {
        val activity = composeTestRule.activity

        // ---- More -> Load & Recovery -------------------------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.more_entry_load_recovery))
        composeTestRule.onNodeWithText(str(Res.string.more_entry_load_recovery)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.load_range_28d))

        // ---- Scroll to the "Muscle load" card (the LazyColumn's last item — not composed
        // until scrolled into view, so it cannot be waited on before telling the list to scroll
        // to it; same idiom as SettingsPersistenceTest.openSettings) --------------------------
        val muscleLoadTitle = str(Res.string.load_muscle_title)
        composeTestRule.verticalScroller().performScrollToNode(hasText(muscleLoadTitle))
        composeTestRule.waitUntilTextExists(muscleLoadTitle)
        composeTestRule.onNodeWithText(muscleLoadTitle).assertExists()

        // ---- "Legs are loaded — an upper-body day fits today" -------------------------------
        val hint = str(Res.string.load_muscle_hint_upper)
        composeTestRule.waitUntilTextExists(hint)
        composeTestRule.onNodeWithText(hint).assertExists()
    }
}
