package com.myhealth.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.MainActivity
import com.myhealth.resources.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PLAN P10.2: toggling "Use wallpaper colours" and changing the sleep target in Settings survives
 * an activity recreation (a configuration change, or — as used here — a fresh process read of the
 * same DataStore/Room-backed state).
 */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {

    private val dynamicColorSwitchTag = "settings_dynamic_color_switch"

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
    fun toggleWallpaperColours_andSleepTarget_persistAcrossRecreate() {
        val activity = composeTestRule.activity

        openSettings(activity)

        composeTestRule.onNodeWithTag(dynamicColorSwitchTag).assertIsOff()
        composeTestRule.onNodeWithTag(dynamicColorSwitchTag).performClick()
        composeTestRule.onNodeWithTag(dynamicColorSwitchTag).assertIsOn()

        // PLAN P14.6 inserted a "Heart-rate zones" section between the profile fields and the app
        // preferences the block above already scrolled past, so the profile item (and "Sleep
        // target" within it) is no longer guaranteed to still be composed here — scroll the list
        // to it explicitly instead of assuming `performScrollTo()` finds an already-live node.
        val sleepTargetLabel = str(Res.string.settings_profile_sleep_target_label)
        composeTestRule.verticalScroller().performScrollToNode(hasText(sleepTargetLabel))
        composeTestRule.onNodeWithText(sleepTargetLabel).performTextReplacement("9.5")
        composeTestRule.waitForIdle()

        // `recreate()` re-creates the Activity in place (a configuration change / fresh process
        // read), which restores the NavController's saved back stack — it lands right back on
        // this same Settings screen rather than on Today. Dumping the semantics tree here (PLAN
        // P10.2 debugging note) confirmed that: re-tapping the bottom-nav "More" tab from a
        // screen outside its hierarchy (Settings is reached by a plain `navigate()` push from the
        // More list, not through the bottom-bar's own save/restore machinery) is a dead click that
        // leaves the app on Settings, so the test must not try to navigate back in — it only needs
        // to scroll the already-showing screen to what it wants to assert on.
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        composeTestRule.verticalScroller().performScrollToNode(hasTestTag(dynamicColorSwitchTag))
        composeTestRule.onNodeWithTag(dynamicColorSwitchTag).assertIsOn()

        // The sleep target field (in the profile item, now two items above the app-preferences
        // item scrolled to above — P14.6's inserted "Heart-rate zones" section) is no longer
        // guaranteed to still be composed here either; scroll back to it before reading its value.
        composeTestRule.verticalScroller().performScrollToNode(hasText(sleepTargetLabel))
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("9.5", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Opens Settings from the bottom-nav "More" tab. [SettingsScreen]'s first `item {}`
     * (`ProfileSection`) is by itself taller than the viewport, so the second item
     * (`AppPreferencesSection`, holding the "Use wallpaper colours" switch) is never composed by a
     * plain `waitUntilTextExists` on its label; the Settings `LazyColumn` itself has to be told to
     * scroll to it (`performScrollToNode`), the same way a real user would swipe down to it.
     */
    private fun openSettings(activity: MainActivity) {
        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.settings_title))
        composeTestRule.onNodeWithText(str(Res.string.settings_title)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.settings_section_profile))
        composeTestRule.verticalScroller().performScrollToNode(hasTestTag(dynamicColorSwitchTag))
    }
}
