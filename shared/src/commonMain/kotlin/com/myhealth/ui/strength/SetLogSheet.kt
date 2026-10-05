package com.myhealth.ui.strength

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.unit.dp
import com.myhealth.domain.engine.strength.ExerciseAnimations
import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.Feedback
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.body.AnimatedBodyFigure
import com.myhealth.ui.common.body.highlightFor

/**
 * The optional per-set log a "Mark done" on a `STRENGTH_*` session with a workout opens (PLAN
 * §4.1/§4.2, P14.7; §P16 "load progression", P16.2): one row per set, pre-filled from
 * [prescriptions] where there is one, each skippable, plus one feedback selector per distinct
 * exercise (four segmented buttons, default `HARD`). [onSave] receives the rows still checked in
 * and the chosen feedback per exercise id; [onSkip] marks the session done with no log at all.
 * Self-contained like `ExercisePickerSheet` — its edits are local, ephemeral form state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetLogSheet(
    workout: StrengthWorkout,
    onSave: (rows: List<SetLogRow>, feedback: Map<String, Feedback>) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** `exerciseId -> ExercisePrescription` (P16.2) — the caller's best estimate of today's
     * prescription for every exercise of [workout], resolved before the sheet opens. An exercise
     * missing from this map falls back to its workout row's own stored numbers, exactly as before
     * P16.2. */
    prescriptions: Map<String, ExercisePrescription> = emptyMap(),
) {
    val sheetState = rememberModalBottomSheetState()
    var rows by remember(workout.id, prescriptions) { mutableStateOf(setLogRowsFor(workout, prescriptions)) }
    var feedbackByExercise by remember(workout.id, prescriptions) {
        mutableStateOf(defaultFeedbackByExercise(rows))
    }
    val grouped = remember(rows) { rows.withIndex().groupBy { it.value.exerciseId } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Text(
            text = stringResource(Res.string.set_log_title_format, workout.name),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        // POLISH-19: the list must yield to the button row below it, or Save/Skip lay out off-screen.
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            grouped.forEach { (exerciseId, indexed) ->
                item(key = "header-$exerciseId") {
                    ExerciseFeedbackRow(
                        exerciseId = exerciseId,
                        exerciseName = indexed.first().value.exerciseName,
                        selected = feedbackByExercise[exerciseId] ?: Feedback.HARD,
                        onSelect = { feedback -> feedbackByExercise = feedbackByExercise + (exerciseId to feedback) },
                    )
                }
                items(indexed, key = { "row-${it.index}" }) { (index, row) ->
                    SetLogRowItem(
                        row = row,
                        onChange = { updated -> rows = rows.toMutableList().also { it[index] = updated } },
                    )
                    HorizontalDivider()
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.set_log_skip))
            }
            TextButton(
                onClick = { onSave(rows.filterNot { it.skipped }, feedbackByExercise) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

/** One exercise's feedback selector (PLAN §P16 "load progression": "chosen once per exercise …
 * four segmented buttons, default `HARD`"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseFeedbackRow(
    exerciseId: String,
    exerciseName: String,
    selected: Feedback,
    onSelect: (Feedback) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val exercise = ExerciseCatalog.byId(exerciseId)
            AnimatedBodyFigure(
                clip = ExerciseAnimations.clipOrStanding(exerciseId),
                highlight = exercise?.let(::highlightFor) ?: emptyMap(),
                sizeDp = 72.dp,
            )
            Text(exerciseName, style = MaterialTheme.typography.titleSmall)
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Feedback.entries.forEachIndexed { index, feedback ->
                SegmentedButton(
                    selected = feedback == selected,
                    onClick = { onSelect(feedback) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = Feedback.entries.size),
                    label = { Text(feedback.label()) },
                )
            }
        }
    }
}

@Composable
private fun SetLogRowItem(row: SetLogRow, onChange: (SetLogRow) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = !row.skipped, onCheckedChange = { checked -> onChange(row.copy(skipped = !checked)) })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.set_log_row_format, row.exerciseName, row.setIndex + 1),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.seconds != null) {
                    NumberField(
                        label = stringResource(Res.string.workout_edit_seconds_label),
                        value = row.seconds.toDouble(),
                        onValueChange = { v -> onChange(row.copy(seconds = v?.toInt())) },
                        decimals = 0,
                        enabled = !row.skipped,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    NumberField(
                        label = stringResource(Res.string.workout_edit_reps_label),
                        value = row.reps?.toDouble(),
                        onValueChange = { v -> onChange(row.copy(reps = v?.toInt())) },
                        decimals = 0,
                        enabled = !row.skipped,
                        modifier = Modifier.weight(1f),
                    )
                }
                NumberField(
                    label = stringResource(Res.string.workout_edit_load_label),
                    value = row.loadKg,
                    onValueChange = { v -> onChange(row.copy(loadKg = v)) },
                    suffix = "kg",
                    enabled = !row.skipped,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
