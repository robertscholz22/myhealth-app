package com.myhealth.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.myhealth.MyHealthApp
import com.myhealth.di.AppGraph
import com.myhealth.domain.model.AppSettings
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.Sex
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.time.LocalDate

/**
 * The [AppGraph] of the process under test — the same instance [com.myhealth.MainActivity] reads
 * (PLAN P10.2). The instrumentation process's [MyHealthApp.onCreate] always runs before any
 * `@Rule`/`@Test` code, so `graph` is guaranteed initialized here.
 */
fun testGraph(): AppGraph {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MyHealthApp
    return app.graph
}

/**
 * Clears every table and resets [AppSettings] to defaults *before* the activity under test
 * launches (`order = 0`, ahead of `createAndroidComposeRule`'s default `order = 1` — the same
 * "outer rule runs first" idiom used to run Hilt injection ahead of an `ActivityScenarioRule`).
 *
 * Each test method then starts from a known-empty app regardless of whatever manual test data or
 * a previous test method left behind in the shared instrumentation process — the [AppGraph] (and
 * the Room/DataStore handles it holds) is created once per process by [MyHealthApp.onCreate] and
 * stays alive across every test method in a run, so this clears through the already-open handles
 * rather than deleting the on-disk files: deleting the files out from under an already-open Room
 * connection would leave it working against an unlinked file instead of actually clearing it.
 */
class ClearAppStateRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val graph = testGraph()
            runBlocking {
                graph.db.clearAllTables()
                graph.settings.update { AppSettings() }
            }
            base.evaluate()
        }
    }
}

/**
 * The screen's vertical scroller. `onNode(hasScrollAction())` is ambiguous as soon as a
 * horizontal row (Settings' equipment chips, a chip `LazyRow`) is composed next to it.
 */
fun ComposeTestRule.verticalScroller(): SemanticsNodeInteraction =
    onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

/**
 * Waits (up to [timeoutMillis]) for a node matching [text] to exist, then leaves it for the
 * caller to act on — a generous, animation-tolerant alternative to asserting immediately after a
 * navigation click (PLAN P10.2: "use waitUntil/waitForIdle with generous timeouts").
 */
fun ComposeTestRule.waitUntilTextExists(
    text: String,
    byContentDescription: Boolean = false,
    timeoutMillis: Long = 10_000,
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        val nodes = if (byContentDescription) {
            onAllNodesWithContentDescription(text)
        } else {
            onAllNodesWithText(text)
        }
        nodes.fetchSemanticsNodes().isNotEmpty()
    }
}

/** A minimal valid [Profile] (PLAN §2.2.1) so a test can skip onboarding and land on Today.
 * [sex] defaults to `MALE`; pass `Sex.FEMALE` for a cycle-tracker test (PLAN §5 P11.3) — a FEMALE
 * profile turns `cycleTrackingEnabled` on via the same repository rule Settings/Onboarding use. */
fun AppGraph.seedProfileBlocking(name: String = "Test Runner", sex: Sex = Sex.MALE) {
    val now = clock.millis()
    runBlocking {
        profileRepo.upsert(
            Profile(
                displayName = name,
                sex = sex,
                birthDay = LocalDate.of(1990, 1, 1).toEpochDay(),
                heightCm = 180.0,
                neatLevel = NeatLevel.LIGHT_ACTIVE,
                goalWeightKg = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
    }
}
