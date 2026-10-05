package com.myhealth.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.MainActivity
import com.myhealth.resources.*
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
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
 * PLAN P12.5: the Bike screen (FTP card + PR tables, P12.4), the Settings cycling fields
 * (indoor trainer + ride sessions cap, P12.4), and a `BIKE_FTP` goal end to end.
 */
@RunWith(AndroidJUnit4::class)
class BikeScreenTest {

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
    fun bikeScreen_showsFtpFromManualOverride_andRideBests() {
        val graph = testGraph()
        val rideDay = LocalDate.now(graph.clock).toEpochDay() - 1

        runBlocking {
            val profile = requireNotNull(graph.profileRepo.getProfile())
            graph.profileRepo.upsert(profile.copy(ftpWattsManual = 250))

            val startAtMillis = rideDay * 86_400_000L + 18 * 3_600_000L
            val session = ActivitySession(
                id = 0L,
                startAtMillis = startAtMillis,
                endAtMillis = startAtMillis + 3_600_000L,
                day = rideDay,
                sportType = SportType.CYCLING,
                sportGroup = SportGroup.CYCLE,
                title = "Test ride",
                durationSec = 3_600,
                elapsedSec = 3_600,
                distanceMeters = 40_000.0,
                activeEnergyKcal = null,
                totalEnergyKcal = null,
                avgHr = null,
                maxHr = null,
                avgSpeedMps = null,
                maxSpeedMps = null,
                avgCadenceSpm = null,
                elevationGainM = null,
                avgPowerW = 220,
                maxPowerW = 300,
                normalizedPowerW = 230,
                trimp = null,
                loadMethod = null,
                rpe = null,
                note = null,
                primarySource = ActivitySource.MANUAL,
                mergedSources = listOf(ActivitySource.MANUAL),
                dedupeBucket = "${SportGroup.CYCLE}|${startAtMillis / 300_000L}",
                userEditedFields = emptyList(),
                hasStreams = false,
                streams = null,
                laps = emptyList(),
                createdAtMillis = graph.clock.millis(),
                updatedAtMillis = graph.clock.millis(),
            )
            val activityId = (graph.activityRepo.upsert(session) as Outcome.Ok).value

            graph.rideBestRepo.upsertAll(
                listOf(
                    RideBest(
                        id = 0L,
                        kind = RideBestKind.POWER_20MIN,
                        value = 260.0,
                        activityId = activityId,
                        day = rideDay,
                        isEstimated = false,
                        createdAtMillis = graph.clock.millis(),
                    ),
                    RideBest(
                        id = 0L,
                        kind = RideBestKind.TIME_40K,
                        value = 4_478.0,
                        activityId = activityId,
                        day = rideDay,
                        isEstimated = true,
                        createdAtMillis = graph.clock.millis(),
                    ),
                ),
            )
        }

        val activity = composeTestRule.activity

        // ---- More -> Bike & power --------------------------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.more_entry_bike))
        composeTestRule.onNodeWithText(str(Res.string.more_entry_bike)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.bike_ftp_title))

        // ---- FTP card: manual override wins, labelled "Manual" ---------------------------
        composeTestRule.waitUntilTextExists("250 W")
        composeTestRule.onNodeWithText("Manual", useUnmergedTree = true).assertExists()

        // ---- Power bests: the 20-minute effort ------------------------------------------
        composeTestRule.waitUntilTextExists(str(Res.string.bike_power_bests_title))
        composeTestRule.onNodeWithText("260 W", useUnmergedTree = true).assertExists()

        // ---- Time bests: the 40 km effort, marked estimated -------------------------------
        composeTestRule.waitUntilTextExists(str(Res.string.bike_time_bests_title))
        val estimatedSuffix = str(Res.string.bike_estimated_suffix)
        composeTestRule.onNodeWithText("1:14:38$estimatedSuffix", useUnmergedTree = true).assertExists()
    }

    @Test
    fun settings_persistsIndoorTrainerAndRideCap() {
        val activity = composeTestRule.activity
        val indoorTrainerTag = "settings_indoor_trainer_switch"
        val rideSessionsTag = "settings_ride_sessions_field"

        openSettings(activity)

        composeTestRule.onNodeWithTag(indoorTrainerTag).performScrollTo().assertIsOff()
        composeTestRule.onNodeWithTag(indoorTrainerTag).performClick()
        composeTestRule.onNodeWithTag(indoorTrainerTag).assertIsOn()

        composeTestRule.onNodeWithTag(rideSessionsTag)
            .performScrollTo()
            .performTextReplacement("2")
        composeTestRule.waitForIdle()

        // Same idiom as SettingsPersistenceTest: `recreate()` restores the NavController's saved
        // back stack, landing right back on Settings, so there is nothing to re-navigate.
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        composeTestRule.verticalScroller().performScrollToNode(hasTestTag(indoorTrainerTag))
        composeTestRule.onNodeWithTag(indoorTrainerTag).assertIsOn()
        composeTestRule.onNodeWithTag(rideSessionsTag).performScrollTo().assertTextContains("2")
    }

    @Test
    fun goalEditor_createsBikeFtpGoal_showingProgressLine() {
        val activity = composeTestRule.activity

        // ---- More -> Goals -> New goal ----------------------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.more_entry_goals))
        composeTestRule.onNodeWithText(str(Res.string.more_entry_goals)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.goals_title))
        composeTestRule.onNodeWithContentDescription(str(Res.string.goals_new_content_description)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.goal_edit_title_new))

        // ---- Type: Bike FTP ----------------------------------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.goal_edit_type_label)).performClick()
        composeTestRule.waitUntilTextExists("Bike FTP")
        composeTestRule.onNodeWithText("Bike FTP").performClick()
        composeTestRule.waitForIdle()

        // ---- Title (required to save) + target watts --------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.goal_edit_title_label))
            .performTextInput("Bike FTP goal")
        composeTestRule.onNodeWithText(str(Res.string.goal_edit_target_ftp_label))
            .performScrollTo()
            .performTextReplacement("300")
        composeTestRule.waitForIdle()

        // ---- Create --------------------------------------------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.goal_edit_create_action)).performClick()

        // ---- Back on the Goals list: the headline reads "300 W FTP" ------------------------
        composeTestRule.waitUntilTextExists("300 W FTP")
    }

    private fun openSettings(activity: MainActivity) {
        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.settings_title))
        composeTestRule.onNodeWithText(str(Res.string.settings_title)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.settings_section_profile))
        composeTestRule.verticalScroller().performScrollToNode(hasTestTag("settings_indoor_trainer_switch"))
    }
}
