package com.myhealth.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SportGroup
import com.myhealth.ui.common.DatePickerField
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.resolve
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

@Composable
fun OnboardingScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph -> OnboardingViewModel(graph.profileRepo, graph.bodyRepo, graph.settings, graph.syncScheduler, graph.clock) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            if (event is OnboardingEvent.Saved) onDone()
        }
    }

    OnboardingContent(
        state = state,
        onDraftChange = vm::updateDraft,
        onBack = vm::back,
        onNext = vm::next,
        modifier = modifier,
    )
}

@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onDraftChange: ((OnboardingDraft) -> OnboardingDraft) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = OnboardingStep.entries
    val stepIndex = steps.indexOf(state.step)

    Column(modifier = modifier.fillMaxSize()) {
        LinearProgressIndicator(
            progress = { (stepIndex + 1f) / steps.size },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(Res.string.common_step_progress, stepIndex + 1, steps.size, state.step.title()),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                when (state.step) {
                    OnboardingStep.IDENTITY -> IdentityStep(state.draft, state.errors, onDraftChange)
                    OnboardingStep.BODY -> BodyStep(state.draft, state.errors, onDraftChange)
                    OnboardingStep.PREFERENCES -> PreferencesStep(state.draft, onDraftChange)
                }
            }
            if (state.saveError != null) {
                item { Text(text = state.saveError.resolve(), color = MaterialTheme.colorScheme.error) }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (stepIndex > 0) {
                OutlinedButton(onClick = onBack, enabled = !state.isSaving) { Text(stringResource(Res.string.action_back)) }
            } else {
                Column {}
            }
            Button(onClick = onNext, enabled = state.canContinue && !state.isSaving) {
                if (state.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text(stringResource(if (state.isLastStep) Res.string.action_finish else Res.string.action_next))
            }
        }
    }
}

@Composable
private fun OnboardingStep.title(): String = when (this) {
    OnboardingStep.IDENTITY -> stringResource(Res.string.onboarding_step_identity)
    OnboardingStep.BODY -> stringResource(Res.string.onboarding_step_body)
    OnboardingStep.PREFERENCES -> stringResource(Res.string.onboarding_step_preferences)
}

@Composable
private fun IdentityStep(
    draft: OnboardingDraft,
    errors: Map<OnboardingField, UiMessage>,
    onDraftChange: ((OnboardingDraft) -> OnboardingDraft) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.onboarding_step_identity)) {
        OutlinedTextField(
            value = draft.displayName,
            onValueChange = { name -> onDraftChange { it.copy(displayName = name) } },
            label = { Text(stringResource(Res.string.onboarding_name_label)) },
            singleLine = true,
            isError = errors.containsKey(OnboardingField.NAME),
            supportingText = errors[OnboardingField.NAME]?.let { { Text(it.resolve()) } },
            modifier = Modifier.fillMaxWidth(),
        )
        SexPicker(sex = draft.sex, onSexChange = { sex -> onDraftChange { it.copy(sex = sex) } })
        DatePickerField(
            label = stringResource(Res.string.onboarding_birth_date_label),
            value = draft.birthDay,
            onValueChange = { day -> onDraftChange { it.copy(birthDay = day) } },
            isError = errors.containsKey(OnboardingField.BIRTH_DATE),
            supportingText = errors[OnboardingField.BIRTH_DATE]?.resolve(),
        )
    }
}

@Composable
private fun SexPicker(sex: Sex, onSexChange: (Sex) -> Unit) {
    DropdownField(
        label = stringResource(Res.string.onboarding_sex_label),
        options = Sex.entries,
        selected = sex,
        optionLabel = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
        onSelect = onSexChange,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BodyStep(
    draft: OnboardingDraft,
    errors: Map<OnboardingField, UiMessage>,
    onDraftChange: ((OnboardingDraft) -> OnboardingDraft) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.onboarding_body_section_title)) {
        NumberField(
            label = stringResource(Res.string.onboarding_height_label),
            value = draft.heightCm,
            onValueChange = { v -> onDraftChange { it.copy(heightCm = v) } },
            suffix = stringResource(Res.string.common_unit_cm),
            decimals = 0,
            isError = errors.containsKey(OnboardingField.HEIGHT),
            supportingText = errors[OnboardingField.HEIGHT]?.resolve(),
        )
        NumberField(
            label = stringResource(Res.string.onboarding_weight_label),
            value = draft.weightKg,
            onValueChange = { v -> onDraftChange { it.copy(weightKg = v) } },
            suffix = stringResource(Res.string.common_unit_kg),
            decimals = 1,
            isError = errors.containsKey(OnboardingField.WEIGHT),
            supportingText = errors[OnboardingField.WEIGHT]?.resolve(),
        )
    }
    SectionCard(title = stringResource(Res.string.onboarding_goals_section_title)) {
        NumberField(
            label = stringResource(Res.string.onboarding_goal_weight_label),
            value = draft.goalWeightKg,
            onValueChange = { v -> onDraftChange { it.copy(goalWeightKg = v) } },
            suffix = stringResource(Res.string.common_unit_kg),
            decimals = 1,
            isError = errors.containsKey(OnboardingField.GOAL_WEIGHT),
            supportingText = errors[OnboardingField.GOAL_WEIGHT]?.resolve(),
        )
        NumberField(
            label = stringResource(Res.string.onboarding_goal_pace_label),
            value = draft.goalPaceKgPerWeek,
            onValueChange = { v -> onDraftChange { it.copy(goalPaceKgPerWeek = v) } },
            suffix = stringResource(Res.string.common_unit_kg_per_week),
            decimals = 2,
            allowNegative = true,
            isError = errors.containsKey(OnboardingField.GOAL_PACE),
            supportingText = errors[OnboardingField.GOAL_PACE]?.resolve()
                ?: stringResource(Res.string.onboarding_goal_pace_hint),
        )
        NeatLevelPicker(level = draft.neatLevel, onLevelChange = { level -> onDraftChange { it.copy(neatLevel = level) } })
    }
}

@Composable
private fun NeatLevelPicker(level: NeatLevel, onLevelChange: (NeatLevel) -> Unit) {
    val labels = mapOf(
        NeatLevel.DESK to stringResource(Res.string.onboarding_neat_desk),
        NeatLevel.LIGHT_ACTIVE to stringResource(Res.string.onboarding_neat_light_active),
        NeatLevel.ACTIVE to stringResource(Res.string.onboarding_neat_active),
        NeatLevel.PHYSICAL_JOB to stringResource(Res.string.onboarding_neat_physical_job),
    )
    DropdownField(
        label = stringResource(Res.string.onboarding_neat_label),
        options = NeatLevel.entries,
        selected = level,
        optionLabel = { labels.getValue(it) },
        onSelect = onLevelChange,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PreferencesStep(
    draft: OnboardingDraft,
    onDraftChange: ((OnboardingDraft) -> OnboardingDraft) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.onboarding_weekly_sessions_title)) {
        SportGroup.entries.filter { it in draft.sessionsPerWeek }.forEach { group ->
            NumberField(
                label = if (group == SportGroup.CYCLE) {
                    stringResource(Res.string.onboarding_ride_sessions_label)
                } else {
                    stringResource(
                        Res.string.onboarding_sport_sessions_label,
                        group.name.lowercase().replaceFirstChar { it.uppercase() },
                    )
                },
                value = draft.sessionsPerWeek[group]?.toDouble(),
                onValueChange = { v ->
                    val count = (v ?: 0.0).toInt().coerceIn(0, 14)
                    onDraftChange { it.copy(sessionsPerWeek = it.sessionsPerWeek + (group to count)) }
                },
                decimals = 0,
            )
        }
    }
    SectionCard(title = stringResource(Res.string.onboarding_recovery_section_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(Res.string.onboarding_mobility_label))
            Switch(
                checked = draft.mobilityOnRestDays,
                onCheckedChange = { v -> onDraftChange { it.copy(mobilityOnRestDays = v) } },
            )
        }
        NumberField(
            label = stringResource(Res.string.onboarding_sleep_target_label),
            value = draft.sleepTargetHours,
            onValueChange = { v -> onDraftChange { it.copy(sleepTargetHours = v) } },
            suffix = stringResource(Res.string.common_unit_hours),
            decimals = 1,
        )
        // P11.3: shown only for FEMALE, on by default — the same switch Settings has, so
        // whichever value is chosen here is exactly what Settings shows afterwards.
        if (draft.sex == Sex.FEMALE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(Res.string.cycle_track_switch_label))
                Switch(
                    checked = draft.cycleTrackingEnabled,
                    onCheckedChange = { v -> onDraftChange { it.copy(cycleTrackingEnabled = v) } },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingContentIdentityPreview() {
    MyHealthTheme(dynamicColor = false) {
        OnboardingContent(state = OnboardingUiState(), onDraftChange = {}, onBack = {}, onNext = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingContentBodyPreview() {
    MyHealthTheme(dynamicColor = false) {
        OnboardingContent(
            state = OnboardingUiState(
                step = OnboardingStep.BODY,
                draft = OnboardingDraft(displayName = "Robert", birthDay = LocalDate(1990, 1, 1)),
            ),
            onDraftChange = {},
            onBack = {},
            onNext = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingContentPreferencesPreview() {
    MyHealthTheme(dynamicColor = false) {
        OnboardingContent(
            state = OnboardingUiState(
                step = OnboardingStep.PREFERENCES,
                draft = OnboardingDraft(
                    displayName = "Robert",
                    birthDay = LocalDate(1990, 1, 1),
                    heightCm = 180.0,
                    weightKg = 78.0,
                ),
            ),
            onDraftChange = {},
            onBack = {},
            onNext = {},
        )
    }
}
