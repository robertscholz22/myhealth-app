package com.myhealth.ui.activities

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.Lap
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.SourceBadgeRow
import com.myhealth.ui.common.displayName
import com.myhealth.ui.theme.MyHealthTheme
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * `@Preview`s for [ActivityDetailScreen], split out so that file stays inside the ~400-line budget
 * of PLAN R10. Preview-only sample data lives here and nowhere else.
 */

@Preview(showBackground = true, name = "Populated")
@Composable
private fun ActivityDetailBodyPreview() {
    MyHealthTheme(dynamicColor = false) {
        ActivityDetailBody(
            state = ActivityDetailUiState(
                isLoading = false,
                activity = previewSession(),
                hrZoneMinutes = listOf(2.0, 5.0, 12.0, 8.0, 1.0),
            ),
            onSaveTitle = {},
            onSaveNote = {},
            onOpenEventPicker = {},
            onUnlinkEvent = {},
            onSaveRpe = {},
        )
    }
}

@Preview(showBackground = true, name = "Loading")
@Composable
private fun ActivityDetailBodyLoadingPreview() {
    MyHealthTheme(dynamicColor = false) {
        ActivityDetailBody(
            state = ActivityDetailUiState(isLoading = true),
            onSaveTitle = {},
            onSaveNote = {},
            onOpenEventPicker = {},
            onUnlinkEvent = {},
            onSaveRpe = {},
        )
    }
}

private fun previewStreams(): ActivityStreams {
    val offsets = (0..1800 step 10).toList()
    return ActivityStreams(
        sampleOffsetsSec = offsets.toIntArray(),
        hr = offsets.map { 120 + (it / 60) % 40 },
        distanceMeters = offsets.map { it * 2.9 }.toDoubleArray(),
        speedMps = offsets.map { 2.9 }.toDoubleArray(),
        altitudeM = offsets.map { 30.0 + (it / 120) % 25 }.toDoubleArray(),
        sampleCount = offsets.size,
        medianIntervalSec = 10.0,
    )
}

private fun previewSession(): ActivitySession = ActivitySession(
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
    note = "Felt good, easy effort.",
    primarySource = ActivitySource.HEALTH_CONNECT,
    mergedSources = listOf(ActivitySource.HEALTH_CONNECT, ActivitySource.FIT_IMPORT),
    dedupeBucket = "RUN|1",
    userEditedFields = emptyList(),
    hasStreams = true,
    streams = previewStreams(),
    laps = listOf(
        Lap(1, 1, 0, 1_757_000_000_000L, 900, 2500.0, 138, 150, 2.8, 180.0),
        Lap(2, 1, 1, 1_757_000_900_000L, 900, 2600.0, 145, 160, 2.9, 190.0),
    ),
    createdAtMillis = 0,
    updatedAtMillis = 0,
)
