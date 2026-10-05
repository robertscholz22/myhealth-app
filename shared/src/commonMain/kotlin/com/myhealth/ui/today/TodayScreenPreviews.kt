package com.myhealth.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.calendar.LinkProposal
import com.myhealth.domain.engine.strength.MuscleLoadEngine
import com.myhealth.domain.engine.strength.MuscleLoadInput
import com.myhealth.domain.engine.strength.MuscleSession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.ui.activities.formatDuration
import com.myhealth.ui.calendar.confidenceLabel
import com.myhealth.ui.calendar.targetProgressRows
import com.myhealth.ui.nutrition.NO_TARGET_MESSAGE
import com.myhealth.ui.nutrition.energyFraction
import com.myhealth.ui.nutrition.remainingLabel
import com.myhealth.ui.nutrition.roundHalfUp
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.ErrorBanner
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.SportIcon
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.StatTile
import com.myhealth.ui.common.displayName
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.TimeZone

/**
 * `@Preview`s for [TodayScreen], split out of `TodayScreen.kt` so that file stays inside the
 * ~400-line budget of PLAN R10. Preview-only sample data lives here and nowhere else.
 */

@Preview(showBackground = true, name = "Populated")
@Composable
private fun TodayContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        TodayContent(
            state = TodayUiState(
                isLoading = false,
                activities = listOf(
                    ActivitySummary(
                        id = 1,
                        startAtMillis = 1_757_000_000_000L,
                        endAtMillis = 1_757_003_600_000L,
                        day = 19980,
                        sportType = SportType.RUN_OUTDOOR,
                        sportGroup = SportGroup.RUN,
                        title = "Morning run",
                        durationSec = 2880,
                        elapsedSec = 3000,
                        distanceMeters = 8320.0,
                        activeEnergyKcal = 540.0,
                        totalEnergyKcal = 640.0,
                        avgHr = 142,
                        maxHr = 168,
                        avgSpeedMps = 2.89,
                        maxSpeedMps = 4.1,
                        avgCadenceSpm = 172.0,
                        elevationGainM = 45.0,
                        trimp = 108.1,
                        loadMethod = LoadMethod.HR_SAMPLES,
                        rpe = null,
                        note = null,
                        primarySource = ActivitySource.HEALTH_CONNECT,
                        mergedSources = listOf(ActivitySource.HEALTH_CONNECT),
                        hasStreams = true,
                    ),
                ),
                latestWeight = null,
                lastSyncSuccessAtMillis = 1_757_000_000_000L,
                latestLoad = DailyLoad(
                    day = 19980,
                    trimp = 108.1,
                    sessionCount = 1,
                    atl = 62.0,
                    ctl = 45.0,
                    acwr = 1.1,
                    tsb = -17.0,
                    monotony = 1.4,
                    strain = 620.0,
                    recoveryScore = null,
                    recoveryBand = null,
                    recoveryConfidence = 0.0,
                    flags = emptyList(),
                    computedAtMillis = 1_757_000_000_000L,
                ),
                muscleLoad = MuscleLoadEngine.compute(
                    MuscleLoadInput(
                        today = 19980,
                        ctl = 45.0,
                        sessions = listOf(
                            MuscleSession(day = 19979, sportGroup = SportGroup.RUN, trimp = 220.0),
                        ),
                    ),
                ),
            ),
            onSyncNow = {},
            onOpenActivity = {},
            onOpenDay = {},
            onOpenNutrition = {},
            onOpenLoad = {},
            onAcceptSuggestion = {},
            onDismissSuggestion = {},
            onMarkPlannedDone = {},
            onOpenTraining = {},
            onReviewSuggestions = {},
            onOpenCycle = {},
        )
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun TodayContentEmptyPreview() {
    MyHealthTheme(dynamicColor = false) {
        TodayContent(
            state = TodayUiState(isLoading = false, lastSyncError = "storage: disk full"),
            onSyncNow = {},
            onOpenActivity = {},
            onOpenDay = {},
            onOpenNutrition = {},
            onOpenLoad = {},
            onAcceptSuggestion = {},
            onDismissSuggestion = {},
            onMarkPlannedDone = {},
            onOpenTraining = {},
            onReviewSuggestions = {},
            onOpenCycle = {},
        )
    }
}
