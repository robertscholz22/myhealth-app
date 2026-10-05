package com.myhealth.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.MainActivity
import com.myhealth.resources.*
import com.myhealth.domain.model.Sex
import com.myhealth.ui.calendar.CYCLE_MARKER_TEST_TAG
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PLAN §5 P11.3: a FEMALE profile has the cycle tracker on by default
 * ([com.myhealth.domain.repository.CycleRepository.isTrackingEnabled] ors the settings flag with
 * `sex == FEMALE`), so "Cycle" shows in More without touching Settings. Logging a period start
 * for today shows the Menstrual phase and "Day 1" on the status card, and the same day gets a
 * cycle marker on the Calendar.
 */
@RunWith(AndroidJUnit4::class)
class CycleScreenTest {

    @get:Rule(order = 0)
    val clearState = ClearAppStateRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun seedFemaleProfile() {
        testGraph().seedProfileBlocking(sex = Sex.FEMALE)
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
    }

    @Test
    fun logPeriodStart_showsMenstrualDayOne_andCalendarMarker() {
        val activity = composeTestRule.activity

        // ---- More -> Cycle -------------------------------------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.more_entry_cycle))
        composeTestRule.onNodeWithText(str(Res.string.more_entry_cycle)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.cycle_empty_title))

        // ---- Log period start (defaults to today) --------------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.cycle_action_log_period_start)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.action_save))
        composeTestRule.onNodeWithText(str(Res.string.action_save)).performClick()

        // ---- Status card: Menstrual, Day 1 ----------------------------------------------------
        composeTestRule.waitUntilTextExists(str(Res.string.cycle_phase_menstrual))
        composeTestRule.waitUntilTextExists("Day 1 of ~28")

        // ---- Calendar: today's cell carries a cycle marker ------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.nav_calendar)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(CYCLE_MARKER_TEST_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
