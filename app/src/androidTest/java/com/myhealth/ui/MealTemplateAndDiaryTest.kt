package com.myhealth.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.MainActivity
import com.myhealth.resources.*
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.MeasureBasis
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PLAN P10.2: a meal template built from one hand-created ingredient (80 g of it), "Log now"
 * from the templates list, and the Nutrition diary showing the logged meal with a positive total.
 */
@RunWith(AndroidJUnit4::class)
class MealTemplateAndDiaryTest {

    @get:Rule(order = 0)
    val clearState = ClearAppStateRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val ingredientName = "Diary Test Oats"
    private val templateName = "Diary Test Breakfast"

    @Before
    fun seedProfileAndIngredient() {
        val graph = testGraph()
        graph.seedProfileBlocking()
        runBlocking {
            val now = graph.clock.millis()
            graph.ingredientRepo.upsert(
                Ingredient(
                    id = 0L,
                    name = ingredientName,
                    brand = null,
                    barcode = null,
                    basis = MeasureBasis.PER_100G,
                    pieceGrams = null,
                    servingGrams = null,
                    servingLabel = null,
                    kcal = 500.0,
                    proteinG = 20.0,
                    carbsG = 50.0,
                    sugarG = null,
                    fatG = 10.0,
                    satFatG = null,
                    fiberG = null,
                    saltG = null,
                    sodiumG = null,
                    isFavorite = false,
                    source = "MANUAL",
                    offProductJson = null,
                    lastUsedAtMillis = null,
                    useCount = 0,
                    archived = false,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                ),
            )
        }
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
    }

    @Test
    fun logTemplate_showsInDiaryWithPositiveTotal() {
        val activity = composeTestRule.activity

        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.more_entry_meal_templates))
        composeTestRule.onNodeWithText(str(Res.string.more_entry_meal_templates)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.mealtpl_new_cd), byContentDescription = true)

        composeTestRule.onNodeWithContentDescription(str(Res.string.mealtpl_new_cd)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.mealtpl_title_new))

        composeTestRule.onNodeWithText(str(Res.string.mealtpl_name_label)).performTextInput(templateName)

        composeTestRule.onNodeWithContentDescription(str(Res.string.mealtpl_add_ingredient_cd)).performClick()
        composeTestRule.waitUntilTextExists(ingredientName)
        composeTestRule.onNodeWithText(ingredientName).performClick()

        // The picker seeds a 100 g default quantity; the template needs 80 g of it.
        composeTestRule.onNodeWithText(str(Res.string.quantity_label)).performTextReplacement("80")
        Espresso.closeSoftKeyboard()
        composeTestRule.waitForIdle()

        // "Save template" is the last item of the edit screen's LazyColumn, below the fold, so it
        // is not composed yet: scroll the list itself to it before clicking.
        val saveLabel = str(Res.string.mealtpl_save_button)
        composeTestRule.verticalScroller().performScrollToNode(hasText(saveLabel))
        composeTestRule.onNodeWithText(saveLabel).performClick()

        // Back on the templates list.
        composeTestRule.waitUntilTextExists(str(Res.string.mealtpl_log_now_button))
        composeTestRule.onNodeWithText(str(Res.string.mealtpl_log_now_button)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.mealtpl_log_button))
        composeTestRule.onNodeWithText(str(Res.string.mealtpl_log_button)).performClick()

        composeTestRule.onNodeWithText(str(Res.string.nav_nutrition)).performClick()
        composeTestRule.waitForIdle()

        // ...and 80 g of a 500 kcal/100 g ingredient contributes exactly 400 kcal, which shows up
        // in the diary header's target-vs-intake total right away (a positive total).
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("400", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The template has no default slot, so it logged under the fallback slot (Lunch), not
        // Breakfast: the item row is further down the diary's LazyColumn than the fold, so the
        // list itself has to be scrolled to it rather than just waiting for its text to appear.
        composeTestRule.verticalScroller().performScrollToNode(hasText(templateName))
    }
}
