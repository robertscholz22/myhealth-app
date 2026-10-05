package com.myhealth.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.MainActivity
import com.myhealth.resources.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PLAN P10.2: with a profile present, every bottom-nav tab shows its own screen, and re-tapping
 * Today after opening Load & Recovery from a Today card returns to the dashboard (POLISH-6: the
 * Today tab does not restore the stack it was left on).
 */
@RunWith(AndroidJUnit4::class)
class BottomNavTest {

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
    fun bottomNav_showsEachScreenAndTodayRoundTrips() {
        val activity = composeTestRule.activity

        // Today (start destination) — distinctive card title, not shared with any bottom-bar label.
        composeTestRule.waitUntilTextExists(str(Res.string.today_load_title))

        // Calendar — key off the FAB's content description; the top-bar title is a dynamic
        // month/year string that would need locale-aware formatting to assert against.
        composeTestRule.onNodeWithText(str(Res.string.nav_calendar)).performClick()
        composeTestRule.waitUntilTextExists(
            str(Res.string.calendar_action_new_event_desc),
            byContentDescription = true,
        )

        // Nutrition — the top-bar title repeats the bottom-bar label, so key off the
        // "copy yesterday" action instead.
        composeTestRule.onNodeWithText(str(Res.string.nav_nutrition)).performClick()
        composeTestRule.waitUntilTextExists(
            str(Res.string.nutrition_copy_yesterday_cd),
            byContentDescription = true,
        )

        // Training — same ambiguity as Nutrition when no plan is active; key off "+ Session".
        composeTestRule.onNodeWithText(str(Res.string.nav_training)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.training_add_session))

        // More — a static hub list; "Load & Recovery" is not a bottom-bar label.
        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.more_entry_load_recovery))

        // Back to Today.
        composeTestRule.onNodeWithText(str(Res.string.nav_today)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.today_load_title))

        // Today -> Load & Recovery (tapping the load card, which is not on the bottom bar) -> Today.
        composeTestRule.onNodeWithText(str(Res.string.today_load_title)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.load_empty_title))

        composeTestRule.onNodeWithText(str(Res.string.nav_today)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.today_load_title))
    }
}
