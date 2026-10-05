package com.myhealth.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.MainActivity
import com.myhealth.resources.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PLAN P10.2: Calendar -> new event (type "Soccer match" via the event-type dropdown, then a
 * title) -> save -> the day's agenda shows it -> delete it via the Day detail overflow menu.
 */
@RunWith(AndroidJUnit4::class)
class CalendarEventTest {

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
    fun createEvent_showsInAgenda_thenDeletesFromDayDetail() {
        val activity = composeTestRule.activity
        val eventTitle = "Sunday match"

        composeTestRule.onNodeWithText(str(Res.string.nav_calendar)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.calendar_action_new_event_desc), byContentDescription = true)

        // Week mode shows the agenda list (month cells only show marker dots, no titles).
        composeTestRule.onNodeWithText(str(Res.string.calendar_mode_week)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(str(Res.string.calendar_action_new_event_desc)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.event_title_new))

        // Event type -> "Soccer match" (this also defaults the Sport field to Soccer match).
        composeTestRule.onNodeWithText(str(Res.string.event_label_type)).performClick()
        composeTestRule.onNodeWithText("Soccer match").performClick()

        composeTestRule.onNodeWithText(str(Res.string.event_label_title)).performTextInput(eventTitle)

        composeTestRule.onNodeWithText(str(Res.string.action_save)).performClick()

        // Back on the Calendar, in Week mode, for the day the event was created on (today).
        composeTestRule.waitUntilTextExists(eventTitle)

        // Open Day detail and delete the event via its overflow menu.
        composeTestRule.onNodeWithText(str(Res.string.agenda_day_detail_action)).performClick()
        composeTestRule.waitUntilTextExists(eventTitle)

        composeTestRule.onNodeWithContentDescription(str(Res.string.daydetail_overflow_more_actions_desc)).performClick()
        composeTestRule.onNodeWithText(str(Res.string.action_delete)).performClick()
        // Confirmation dialog's own "Delete" button.
        composeTestRule.onNodeWithText(str(Res.string.action_delete)).performClick()

        composeTestRule.waitUntilTextExists(str(Res.string.daydetail_empty_events))
    }
}
