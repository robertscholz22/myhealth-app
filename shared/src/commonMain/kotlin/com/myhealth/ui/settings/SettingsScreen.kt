package com.myhealth.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.bike.FtpSource
import com.myhealth.domain.model.AppSettings
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SportGroup
import com.myhealth.ui.common.DatePickerField
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.ONBOARDING_SPORT_GROUPS
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.decodePreferredSports
import com.myhealth.ui.common.encodePreferredSports
import com.myhealth.ui.common.resolve
import com.myhealth.ui.common.usText
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val vm = rememberVm { graph ->
        SettingsViewModel(
            graph.profileRepo,
            graph.settings,
            graph.importRepo,
            graph.rideBestRepo,
            graph.activityRepo,
            graph.syncScheduler,
            graph.clock,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // The orphan-cleanup result (or its failure) is reported once, then cleared.
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.resolveText())
            vm.consumeMessage()
        }
    }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { innerPadding ->
        SettingsContent(
            state = state,
            onProfileChange = vm::onProfileChange,
            onSettingsChange = vm::onSettingsChange,
            onRemoveOrphanedImportData = vm::removeOrphanedImportData,
            onRecomputeTrainingLoad = vm::recomputeTrainingLoad,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onProfileChange: (Profile) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    onRemoveOrphanedImportData: () -> Unit,
    onRecomputeTrainingLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val profile = state.profile
        if (profile != null) {
            item { ProfileSection(profile = profile, onProfileChange = onProfileChange) }
            item {
                CyclingSection(profile = profile, ftpEstimate = state.ftpEstimate, onProfileChange = onProfileChange)
            }
            item { HeartRateZonesSection(profile = profile, onProfileChange = onProfileChange) }
            item { StrengthEquipmentSection(profile = profile, onProfileChange = onProfileChange) }
        } else if (!state.isLoading) {
            item { Text(stringResource(Res.string.settings_no_profile)) }
        }
        item { AppPreferencesSection(settings = state.settings, onSettingsChange = onSettingsChange) }
        item {
            AdvancedSection(
                settings = state.settings,
                onSettingsChange = onSettingsChange,
                onRemoveOrphanedImportData = onRemoveOrphanedImportData,
                onRecomputeTrainingLoad = onRecomputeTrainingLoad,
            )
        }
    }
}

@Composable
private fun ProfileSection(profile: Profile, onProfileChange: (Profile) -> Unit) {
    SectionCard(title = stringResource(Res.string.settings_section_profile)) {
        OutlinedTextField(
            value = profile.displayName,
            onValueChange = { onProfileChange(profile.copy(displayName = it)) },
            label = { Text(stringResource(Res.string.settings_profile_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownField(
            label = stringResource(Res.string.settings_profile_sex_label),
            options = Sex.entries,
            selected = profile.sex,
            optionLabel = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
            onSelect = { onProfileChange(profile.copy(sex = it)) },
        )
        DatePickerField(
            label = stringResource(Res.string.settings_profile_birth_date_label),
            value = LocalDate.fromEpochDays(profile.birthDay),
            onValueChange = { onProfileChange(profile.copy(birthDay = it.toEpochDays())) },
        )
        NumberField(
            label = stringResource(Res.string.settings_profile_height_label),
            value = profile.heightCm,
            onValueChange = { it?.let { v -> onProfileChange(profile.copy(heightCm = v)) } },
            suffix = stringResource(Res.string.settings_unit_cm),
            decimals = 0,
        )
        NumberField(
            label = stringResource(Res.string.settings_profile_goal_weight_label),
            value = profile.goalWeightKg,
            onValueChange = { onProfileChange(profile.copy(goalWeightKg = it)) },
            suffix = stringResource(Res.string.settings_unit_kg),
            decimals = 1,
        )
        NumberField(
            label = stringResource(Res.string.settings_profile_goal_pace_label),
            value = profile.goalPaceKgPerWeek,
            onValueChange = { it?.let { v -> onProfileChange(profile.copy(goalPaceKgPerWeek = v)) } },
            suffix = stringResource(Res.string.settings_unit_kg_per_week),
            decimals = 2,
            allowNegative = true,
        )
        DropdownField(
            label = stringResource(Res.string.settings_profile_neat_level_label),
            options = NeatLevel.entries,
            selected = profile.neatLevel,
            optionLabel = { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) },
            onSelect = { onProfileChange(profile.copy(neatLevel = it)) },
        )
        NumberField(
            label = stringResource(Res.string.settings_profile_resting_hr_label),
            value = profile.restingHrManual?.toDouble(),
            onValueChange = { onProfileChange(profile.copy(restingHrManual = it?.toInt())) },
            suffix = stringResource(Res.string.settings_unit_bpm),
            decimals = 0,
        )
        NumberField(
            label = stringResource(Res.string.settings_profile_max_hr_label),
            value = profile.maxHrManual?.toDouble(),
            onValueChange = { onProfileChange(profile.copy(maxHrManual = it?.toInt())) },
            suffix = stringResource(Res.string.settings_unit_bpm),
            decimals = 0,
        )
        NumberField(
            label = stringResource(Res.string.settings_profile_sleep_target_label),
            value = profile.sleepTargetHours,
            onValueChange = { it?.let { v -> onProfileChange(profile.copy(sleepTargetHours = v)) } },
            suffix = stringResource(Res.string.settings_unit_hours),
            decimals = 1,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.settings_profile_mobility_rest_days_label))
            Switch(
                checked = profile.mobilityOnRestDays,
                onCheckedChange = { onProfileChange(profile.copy(mobilityOnRestDays = it)) },
            )
        }
        PreferredSportsFields(profile = profile, onProfileChange = onProfileChange)
    }
}

/**
 * The weekly session caps, one field per [ONBOARDING_SPORT_GROUPS] entry (P12.3 appended `CYCLE`
 * here so the cap that gates the suggester's rides — `BikeRules.isBikeEnabled`, C10 — is reachable
 * from Settings, not only from onboarding).
 */
@Composable
private fun PreferredSportsFields(profile: Profile, onProfileChange: (Profile) -> Unit) {
    val sessions = decodePreferredSports(profile.preferredSportsJson)
    ONBOARDING_SPORT_GROUPS.forEach { group ->
        NumberField(
            label = if (group == SportGroup.CYCLE) {
                stringResource(Res.string.settings_profile_ride_sessions_label)
            } else {
                stringResource(
                    Res.string.settings_profile_sport_sessions_cap_format,
                    group.name.lowercase().replaceFirstChar(Char::uppercase),
                )
            },
            value = (sessions[group] ?: 0).toDouble(),
            onValueChange = { v ->
                val updated = sessions + (group to (v ?: 0.0).toInt().coerceIn(0, 14))
                onProfileChange(profile.copy(preferredSportsJson = encodePreferredSports(updated)))
            },
            // Testing hook (PLAN P10.2 convention, P12.5): the CYCLE cap is otherwise
            // indistinguishable from the other sport caps by anything but its label text.
            modifier = if (group == SportGroup.CYCLE) Modifier.testTag("settings_ride_sessions_field") else Modifier,
            decimals = 0,
        )
    }
}

/** FTP override and indoor-trainer flag (PLAN "UI.", P12.4) — the cycling half of Settings. */
@Composable
private fun CyclingSection(profile: Profile, ftpEstimate: FtpEstimate?, onProfileChange: (Profile) -> Unit) {
    SectionCard(title = stringResource(Res.string.settings_section_cycling)) {
        NumberField(
            label = stringResource(Res.string.settings_profile_ftp_override_label),
            value = profile.ftpWattsManual?.toDouble(),
            onValueChange = { onProfileChange(profile.copy(ftpWattsManual = it?.toInt())) },
            suffix = stringResource(Res.string.settings_unit_watts),
            decimals = 0,
            supportingText = ftpOverrideHint(ftpEstimate),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.settings_profile_indoor_trainer_label))
            Switch(
                checked = profile.indoorTrainerAvailable,
                onCheckedChange = { onProfileChange(profile.copy(indoorTrainerAvailable = it)) },
                // Testing hook (PLAN P10.2 convention, P12.5), matching `settings_dynamic_color_switch`.
                modifier = Modifier.testTag("settings_indoor_trainer_switch"),
            )
        }
    }
}

/** "Estimated 247 W from a ride on 2 Sep." — `null` shows no hint at all (PLAN "UI." example). */
private fun ftpOverrideHint(ftp: FtpEstimate?): String? {
    if (ftp == null) return null
    val basis = ftp.basisDay?.let { " on ${formatFtpBasisDate(it)}" }.orEmpty()
    val source = when (ftp.source) {
        FtpSource.STREAM_20MIN -> "a 20-minute best"
        FtpSource.SESSION_NP -> "a ride"
        FtpSource.MANUAL -> return null // never produced: the hint always resolves with manual = null
    }
    return "Estimated ${ftp.watts} W from $source$basis."
}

private fun formatFtpBasisDate(epochDay: Long): String =
    LocalDate.fromEpochDays(epochDay).usText("d MMM")

@Preview(showBackground = true)
@Composable
private fun SettingsContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        SettingsContent(
            state = SettingsUiState(
                isLoading = false,
                profile = Profile(
                    displayName = "Robert",
                    sex = Sex.MALE,
                    birthDay = LocalDate(1990, 1, 1).toEpochDays(),
                    heightCm = 180.0,
                    createdAtMillis = 0,
                    updatedAtMillis = 0,
                ),
            ),
            onProfileChange = {},
            onSettingsChange = {},
            onRemoveOrphanedImportData = {},
            onRecomputeTrainingLoad = {},
        )
    }
}
