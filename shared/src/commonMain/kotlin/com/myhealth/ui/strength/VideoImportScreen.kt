package com.myhealth.ui.strength

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.engine.strength.video.VideoWorkoutBuilder
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.resources.*
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.body.BodyFigure
import com.myhealth.ui.common.stringResource

/**
 * New video workout / "Check exercises again" (P26.4). [createdExerciseId] is what the exercise
 * editor handed back for the row whose "Create … as new exercise" opened it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoImportScreen(
    workoutId: Long?,
    sharedUrl: String?,
    sharedTitle: String?,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    onCreateExercise: (String) -> Unit,
    createdExerciseId: String?,
    onCreatedConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVm { graph ->
        VideoImportViewModel(
            graph.strengthRepo,
            graph.userExerciseRepo,
            graph.videoTitleSource,
            graph.clock,
            workoutId,
            sharedUrl,
            sharedTitle,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var openRow by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm) { vm.saved.collect { onSaved(it) } }
    LaunchedEffect(createdExerciseId) {
        if (createdExerciseId != null) {
            vm.onCreated(createdExerciseId)
            onCreatedConsumed()
        }
    }

    val checking = state.step == VideoImportStep.CHECK
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (checking) Res.string.video_import_check_title else Res.string.video_import_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (checking && workoutId == null) vm.back() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    if (checking) {
                        TextButton(onClick = { vm.save() }, enabled = !state.saving, modifier = Modifier.testTag("video_import_save")) {
                            Text(stringResource(Res.string.action_save))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val contentModifier = Modifier.fillMaxSize().padding(innerPadding)
        when {
            state.loading -> Unit
            checking -> VideoCheckContent(
                state = state,
                library = vm.library,
                onOpenRow = { openRow = it },
                onSkipIncluded = vm::setSkipIncluded,
                onKind = vm::setKind,
                onEditVideo = vm::back,
                modifier = contentModifier,
            )
            else -> VideoStepContent(
                state = state,
                onUrl = vm::setUrl,
                onTitle = vm::setTitle,
                onRefetch = vm::refetchTitle,
                onName = vm::setName,
                onDuration = vm::setDuration,
                onText = vm::setText,
                onNext = vm::next,
                modifier = contentModifier,
            )
        }
    }

    val row = state.rows.firstOrNull { it.key == openRow }
    if (row != null) {
        ImportRowSheet(
            row = row,
            library = vm.library,
            onDismiss = { openRow = null },
            onChoose = { id -> vm.choose(row.key, id); openRow = null },
            onExclude = { excluded -> vm.setExcluded(row.key, excluded); openRow = null },
            onAmounts = { sets, reps, seconds -> vm.setAmounts(row.key, sets, reps, seconds) },
            onCreate = {
                vm.startCreate(row.key)
                openRow = null
                onCreateExercise(row.name)
            },
        )
    }

    if (state.confirmUnmatched) {
        AlertDialog(
            onDismissRequest = vm::dismissUnmatched,
            title = { Text(stringResource(Res.string.video_import_unmatched_title)) },
            text = { Text(stringResource(Res.string.video_import_unmatched_message, state.unmatchedCount)) },
            confirmButton = { TextButton(onClick = { vm.save(force = true) }) { Text(stringResource(Res.string.video_import_save_anyway)) } },
            dismissButton = { TextButton(onClick = vm::dismissUnmatched) { Text(stringResource(Res.string.video_import_keep_checking)) } },
        )
    }
}

@Composable
private fun VideoStepContent(
    state: VideoImportUiState,
    onUrl: (String) -> Unit,
    onTitle: (String) -> Unit,
    onRefetch: () -> Unit,
    onName: (String) -> Unit,
    onDuration: (Int?) -> Unit,
    onText: (String) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).imePadding().padding(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.url,
            onValueChange = onUrl,
            label = { Text(stringResource(Res.string.video_import_link_label)) },
            placeholder = { Text("https://…") },
            isError = state.showErrors && state.urlError,
            supportingText = if (state.showErrors && state.urlError) {
                { Text(stringResource(Res.string.video_import_error_link)) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("video_import_link"),
        )
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitle,
            label = { Text(stringResource(Res.string.video_import_video_title_label)) },
            supportingText = { Text(stringResource(Res.string.video_import_title_hint)) },
            singleLine = true,
            trailingIcon = {
                if (state.titleLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefetch) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.video_import_fetch_title))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.name,
            onValueChange = onName,
            label = { Text(stringResource(Res.string.workout_edit_name_label)) },
            isError = state.showErrors && state.nameError,
            supportingText = if (state.showErrors && state.nameError) {
                { Text(stringResource(Res.string.workout_edit_error_name_required)) }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("video_import_name"),
        )
        OutlinedTextField(
            value = state.durationMin?.toString().orEmpty(),
            onValueChange = { text -> onDuration(text.filter(Char::isDigit).take(3).toIntOrNull()) },
            label = { Text(stringResource(Res.string.video_import_duration_label)) },
            suffix = { Text(stringResource(Res.string.video_import_minutes_suffix)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("video_import_duration"),
        )
        OutlinedTextField(
            value = state.text,
            onValueChange = onText,
            label = { Text(stringResource(Res.string.video_import_list_label)) },
            placeholder = { Text(stringResource(Res.string.video_import_list_placeholder)) },
            isError = state.showErrors && state.textError,
            supportingText = {
                Text(
                    stringResource(
                        if (state.showErrors && state.textError) Res.string.video_import_error_list else Res.string.video_import_list_hint,
                    ),
                )
            },
            minLines = 8,
            modifier = Modifier.fillMaxWidth().testTag("video_import_list"),
        )
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().testTag("video_import_next")) {
            Text(stringResource(Res.string.video_import_next))
        }
    }
}

@Composable
private fun VideoCheckContent(
    state: VideoImportUiState,
    library: ExerciseLibrary,
    onOpenRow: (String) -> Unit,
    onSkipIncluded: (Int, Boolean) -> Unit,
    onKind: (StrengthWorkoutKind?) -> Unit,
    onEditVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSkipped by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(SCREEN_PADDING), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.name, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onEditVideo, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(Res.string.video_import_edit_video))
                }
                BodyFigure(highlight = state.highlight(library), modifier = Modifier.fillMaxWidth().height(160.dp))
                Text(
                    text = stringResource(
                        Res.string.video_import_summary_format,
                        state.rows.count { it.status == ImportRowStatus.MATCHED },
                        state.checkCount,
                        state.unmatchedCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("video_import_summary"),
                )
                val auto = stringResource(
                    Res.string.video_import_kind_auto_format,
                    VideoWorkoutBuilder.deriveKind(state.videoRows(library)).label(),
                )
                DropdownField(
                    label = stringResource(Res.string.workout_edit_kind_label),
                    options = listOf<StrengthWorkoutKind?>(null) + StrengthWorkoutKind.entries.filter { it != StrengthWorkoutKind.CUSTOM },
                    selected = state.kindOverride,
                    optionLabel = { it?.label() ?: auto },
                    onSelect = onKind,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(Res.string.video_import_rows_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
        items(state.rows, key = { it.key }) { row ->
            ImportRowItem(row = row, library = library, onClick = { onOpenRow(row.key) })
            HorizontalDivider()
        }
        val skipped = state.skippedLines
        if (skipped.isNotEmpty()) {
            item {
                TextButton(onClick = { showSkipped = !showSkipped }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(Res.string.video_import_skipped_format, skipped.size))
                }
            }
            if (showSkipped) {
                items(skipped, key = { "skip-${it.first}" }) { (index, line) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SCREEN_PADDING),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = index in state.includedSkips, onCheckedChange = { onSkipIncluded(index, it) })
                        Text(line.raw, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportRowItem(row: ImportRow, library: ExerciseLibrary, onClick: () -> Unit) {
    val exercise = row.chosenId?.let(library::byId)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = SCREEN_PADDING, vertical = 10.dp)
            .testTag("video_row_${row.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = when {
                    row.excluded -> stringResource(Res.string.video_import_row_excluded)
                    exercise != null -> "→ ${exercise.name}"
                    else -> stringResource(Res.string.video_import_row_not_found)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (exercise == null || row.excluded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(amountLabel(row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusChip(row.status)
    }
}

@Composable
private fun amountLabel(row: ImportRow): String {
    val amount = when {
        row.seconds != null -> "${row.sets} × ${row.seconds} s"
        row.reps != null -> "${row.sets} × ${row.reps}"
        else -> stringResource(Res.string.video_import_sets_format, row.sets)
    }
    return if (row.perSide) "$amount · ${stringResource(Res.string.video_import_each_side)}" else amount
}

@Composable
private fun StatusChip(status: ImportRowStatus) {
    val (label, color) = when (status) {
        ImportRowStatus.MATCHED -> stringResource(Res.string.video_import_status_matched) to MaterialTheme.colorScheme.primary
        ImportRowStatus.CHECK -> stringResource(Res.string.video_import_status_check) to MaterialTheme.colorScheme.tertiary
        ImportRowStatus.NOT_FOUND -> stringResource(Res.string.video_import_status_not_found) to MaterialTheme.colorScheme.error
        ImportRowStatus.EXCLUDED -> stringResource(Res.string.video_import_status_skipped) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(disabledLabelColor = color),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportRowSheet(
    row: ImportRow,
    library: ExerciseLibrary,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
    onExclude: (Boolean) -> Unit,
    onAmounts: (Int, Int?, Int?) -> Unit,
    onCreate: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        ExercisePickerSheet(
            onPick = { onChoose(it.id) },
            onDismiss = { picking = false },
            onCreate = { onCreate() },
        )
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(row.sourceLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val candidates = row.match.candidates.map { it.exercise }.let { list ->
                val chosen = row.chosenId?.let(library::byId)
                if (chosen != null && list.none { it.id == chosen.id }) listOf(chosen) + list else list
            }
            if (candidates.isNotEmpty()) {
                Text(stringResource(Res.string.video_import_suggestions), style = MaterialTheme.typography.labelLarge)
                candidates.forEach { exercise ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(exercise.id) }.testTag("video_candidate_${exercise.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = !row.excluded && row.chosenId == exercise.id, onClick = { onChoose(exercise.id) })
                        Column {
                            Text(exercise.name, style = MaterialTheme.typography.bodyLarge)
                            Text(exerciseRowSubtitle(exercise), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.video_import_search))
            }
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().testTag("video_import_create")) {
                Text(stringResource(Res.string.video_import_create_format, row.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { onExclude(!row.excluded) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (row.excluded) Res.string.video_import_include else Res.string.video_import_not_an_exercise))
            }
            HorizontalDivider()
            AmountEditor(row, onAmounts)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AmountEditor(row: ImportRow, onAmounts: (Int, Int?, Int?) -> Unit) {
    var timed by remember(row.key) { mutableStateOf(row.seconds != null || row.reps == null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = row.sets.toString(),
            onValueChange = { t -> t.filter(Char::isDigit).take(2).toIntOrNull()?.let { onAmounts(it, row.reps, row.seconds) } },
            label = { Text(stringResource(Res.string.video_import_sets_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(96.dp),
        )
        val value = if (timed) row.seconds else row.reps
        OutlinedTextField(
            value = value?.toString().orEmpty(),
            onValueChange = { t ->
                val n = t.filter(Char::isDigit).take(3).toIntOrNull()
                if (timed) onAmounts(row.sets, null, n) else onAmounts(row.sets, n, null)
            },
            label = { Text(stringResource(if (timed) Res.string.video_import_seconds_label else Res.string.video_import_reps_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(120.dp),
        )
        TextButton(onClick = {
            timed = !timed
            if (timed) onAmounts(row.sets, null, row.reps) else onAmounts(row.sets, row.seconds, null)
        }) {
            Text(stringResource(if (timed) Res.string.video_import_use_reps else Res.string.video_import_use_seconds))
        }
    }
}
