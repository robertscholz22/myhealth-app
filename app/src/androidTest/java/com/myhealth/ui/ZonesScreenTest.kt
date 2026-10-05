package com.myhealth.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myhealth.MainActivity
import com.myhealth.resources.*
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivityStreams
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
 * PLAN P14.9: the "Zones & paces" screen (More entry, P14.6) shows the five named HR zones for a
 * profile-only zone model, and at least one measured pace band once a recent run carries HR +
 * speed streams (P14.2's [com.myhealth.domain.engine.running.PaceZoneEngine]).
 */
@RunWith(AndroidJUnit4::class)
class ZonesScreenTest {

    @get:Rule(order = 0)
    val clearState = ClearAppStateRule()

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun seedProfileAndRun() {
        val graph = testGraph()
        graph.seedProfileBlocking()

        runBlocking {
            val today = LocalDate.now(graph.clock).toEpochDay()
            val runDay = today - 3
            val startAtMillis = runDay * 86_400_000L + 7 * 3_600_000L

            // 40 minutes at 1 Hz: HR ~140 bpm (a solid Z2 for the default, HR-reserve-based zone
            // model) at 3.0 m/s — well past the 10-minute warm-up skip (PLAN §3.10.2 sample rule 1),
            // giving PaceZoneEngine at least one non-empty (LOW-confidence-or-better) band.
            val sampleCount = 2_401
            val offsets = IntArray(sampleCount) { it }
            val hr = List<Int?>(sampleCount) { 140 }
            val speed = DoubleArray(sampleCount) { 3.0 }
            val streams = ActivityStreams(
                sampleOffsetsSec = offsets,
                hr = hr,
                distanceMeters = null,
                speedMps = speed,
                sampleCount = sampleCount,
                medianIntervalSec = 1.0,
            )

            val session = ActivitySession(
                id = 0L,
                startAtMillis = startAtMillis,
                endAtMillis = startAtMillis + 2_400_000L,
                day = runDay,
                sportType = SportType.RUN_OUTDOOR,
                sportGroup = SportGroup.RUN,
                title = "Zone test run",
                durationSec = 2_400,
                elapsedSec = 2_400,
                distanceMeters = 7_200.0,
                activeEnergyKcal = null,
                totalEnergyKcal = null,
                avgHr = 140,
                maxHr = 145,
                avgSpeedMps = 3.0,
                maxSpeedMps = 3.0,
                avgCadenceSpm = null,
                elevationGainM = null,
                trimp = null,
                loadMethod = null,
                rpe = null,
                note = null,
                primarySource = ActivitySource.MANUAL,
                mergedSources = listOf(ActivitySource.MANUAL),
                dedupeBucket = "${SportGroup.RUN}|${startAtMillis / 300_000L}",
                userEditedFields = emptyList(),
                hasStreams = true,
                streams = streams,
                laps = emptyList(),
                createdAtMillis = graph.clock.millis(),
                updatedAtMillis = graph.clock.millis(),
            )
            (graph.activityRepo.upsert(session) as Outcome.Ok)
        }

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
    }

    @Test
    fun zonesScreen_showsFiveZoneRows_andAPaceBand() {
        val activity = composeTestRule.activity

        // ---- More -> Zones & paces ---------------------------------------------------------
        composeTestRule.onNodeWithText(str(Res.string.nav_more)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.more_entry_zones))
        composeTestRule.onNodeWithText(str(Res.string.more_entry_zones)).performClick()
        composeTestRule.waitUntilTextExists(str(Res.string.zones_section_zones_title))

        // ---- Five zone rows, Z1..Z5, each with its named zone -------------------------------
        val zoneRowPrefixes = listOf(
            "Z1 · Recovery",
            "Z2 · Endurance",
            "Z3 · Tempo",
            "Z4 · Threshold",
            "Z5 · VO2max",
        )
        zoneRowPrefixes.forEach { prefix ->
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                composeTestRule.onAllNodesWithText(prefix, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText(prefix, substring = true).assertExists()
        }

        // ---- At least one pace band row shows a measured "/km" band -------------------------
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("/km", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
