package com.myhealth.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.MainActivity
import com.myhealth.resources.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PLAN P10.2: hand-create an ingredient (name + kcal/protein/carbs/fat), find it again by search,
 * then delete it (an unused ingredient is deleted outright rather than archived — §2.2.5).
 */
@RunWith(AndroidJUnit4::class)
class IngredientTest {

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
    fun createIngredient_findsItBySearch_thenDeletes() {
        val activity = composeTestRule.activity
        val name = "Test Oatmeal"

        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.more_entry_ingredients))
        composeTestRule.onNodeWithText(str(Res.string.more_entry_ingredients)).performClick()
        composeTestRule.waitUntilTextExists(
            str(Res.string.ingredients_new_content_description),
            byContentDescription = true,
        )

        composeTestRule.onNodeWithContentDescription(str(Res.string.ingredients_new_content_description))
            .performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.ingredient_edit_title_new))

        composeTestRule.onNodeWithText(str(Res.string.ingredient_edit_name_label)).performTextInput(name)

        // The energy/macro fields sit in cards below the fold of the ingredient edit LazyColumn,
        // so they are not composed yet: scroll the list itself to each field's node before typing
        // into it (PLAN P10.2 test-support notes on `performScrollToNode`).
        val caloriesLabel = str(Res.string.ingredient_edit_calories_label)
        composeTestRule.verticalScroller().performScrollToNode(hasText(caloriesLabel))
        composeTestRule.onNodeWithText(caloriesLabel).performTextInput("350")

        val proteinLabel = str(Res.string.ingredient_edit_protein_label)
        composeTestRule.verticalScroller().performScrollToNode(hasText(proteinLabel))
        composeTestRule.onNodeWithText(proteinLabel).performTextInput("12")

        val carbsLabel = str(Res.string.ingredient_edit_carbs_label)
        composeTestRule.verticalScroller().performScrollToNode(hasText(carbsLabel))
        composeTestRule.onNodeWithText(carbsLabel).performTextInput("60")

        val fatLabel = str(Res.string.ingredient_edit_fat_label)
        composeTestRule.verticalScroller().performScrollToNode(hasText(fatLabel))
        composeTestRule.onNodeWithText(fatLabel).performTextInput("6")

        // The Save button is a fixed footer below the LazyColumn, not a list item, so it is
        // already on screen and needs no scroll.
        composeTestRule.onNodeWithText(str(Res.string.action_save)).performClick()

        // Back on the list with an empty query: the new ingredient is there.
        composeTestRule.waitUntilTextExists(name)

        // Search finds it too. A partial query (rather than the full name) keeps the search
        // field's own current-text node from colliding with the exact-text match on the list row.
        composeTestRule.onNodeWithText(str(Res.string.ingredients_search_label)).performTextInput("Oatmeal")
        composeTestRule.waitUntilTextExists(name)

        // Open it and delete it (unused, so this is a real delete, not an archive).
        composeTestRule.onNodeWithText(name).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.ingredient_edit_delete_content_description), byContentDescription = true)
        composeTestRule.onNodeWithContentDescription(str(Res.string.ingredient_edit_delete_content_description))
            .performClick()
        composeTestRule.onNodeWithText(str(Res.string.action_delete)).performClick()

        composeTestRule.waitUntilTextExists(str(Res.string.ingredients_empty_title))
    }
}
