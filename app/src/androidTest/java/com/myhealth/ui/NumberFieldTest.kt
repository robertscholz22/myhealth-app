package com.myhealth.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.ui.common.NumberField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P19 verification: onboarding maps an empty session-count field to 0, which used to write "0"
 * back into the field the user had just cleared — typing 3 then gave "30", clamped to 14.
 */
@RunWith(AndroidJUnit4::class)
class NumberFieldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun cleared_field_mapped_to_zero_takes_the_typed_value() {
        var stored = 2
        composeTestRule.setContent {
            var count by remember { mutableStateOf(2) }
            NumberField(
                label = "Run sessions / week",
                value = count.toDouble(),
                onValueChange = { v ->
                    count = (v ?: 0.0).toInt().coerceIn(0, 14)
                    stored = count
                },
                decimals = 0,
            )
        }

        val field = composeTestRule.onNodeWithText("Run sessions / week")
        field.performTextClearance()
        composeTestRule.waitForIdle()
        field.performTextInput("3")
        composeTestRule.waitForIdle()

        assertEquals(3, stored)
        composeTestRule.onNodeWithText("3").assertExists()
    }
}
