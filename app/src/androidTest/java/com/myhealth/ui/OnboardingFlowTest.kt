package com.myhealth.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.MainActivity
import com.myhealth.resources.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PLAN P10.2: a fresh app (no `profile` row — [ClearAppStateRule] runs before the activity
 * launches) shows Onboarding; filling every step and finishing lands on Today.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {

    @get:Rule(order = 0)
    val clearState = ClearAppStateRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboarding_completesAndLandsOnToday() {
        val activity = composeTestRule.activity

        // ---- Step 1: About you ------------------------------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.onboarding_name_label))
            .performTextInput("Robin Test")

        composeTestRule.onNodeWithText(str(Res.string.onboarding_birth_date_label))
            .performClick()
        // The M3 DatePicker opens in calendar mode; switch to its text-input mode and type a
        // plain 8-digit date (en-US device locale => MM/dd/yyyy, auto-delimited as you type) so
        // the test never depends on which day/month is showing in the calendar grid.
        composeTestRule.onNodeWithContentDescription("Switch to text input mode").performClick()
        // The birth-date field itself is a read-only OutlinedTextField that also exposes a
        // SetText semantics action, so a bare hasSetTextAction() matches both it and the
        // DatePickerDialog's actual text-input field: scope to the dialog.
        composeTestRule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("01011990")
        composeTestRule.onNodeWithText(str(Res.string.common_ok)).performClick()

        composeTestRule.onNodeWithText(str(Res.string.action_next)).performClick()
        composeTestRule.waitForIdle()

        // ---- Step 2: Body & goals ----------------------------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.onboarding_height_label))
            .performScrollTo()
            .performTextInput("180")
        composeTestRule.onNodeWithText(str(Res.string.onboarding_weight_label))
            .performScrollTo()
            .performTextInput("75")

        composeTestRule.onNodeWithText(str(Res.string.action_next)).performClick()
        composeTestRule.waitForIdle()

        // ---- Step 3: Preferences (all optional) -> Finish -----------------------------------
        composeTestRule.onNodeWithText(str(Res.string.action_finish)).performClick()

        // ---- Today -------------------------------------------------------------------------
        val nutritionCardTitle = str(Res.string.today_nutrition_title)
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText(nutritionCardTitle).fetchSemanticsNodes().isNotEmpty()
        }
        // The bottom-nav "Nutrition" tab label and Today's "Nutrition" card title share the same
        // string, so both nodes must be present once onboarding has handed off to Today.
        composeTestRule.onAllNodesWithText(nutritionCardTitle).assertCountEquals(2)
    }
}
