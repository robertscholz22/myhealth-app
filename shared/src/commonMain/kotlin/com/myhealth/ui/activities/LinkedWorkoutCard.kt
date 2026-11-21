package com.myhealth.ui.activities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.resources.*
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.stringResource
import com.myhealth.ui.strength.label

/**
 * P26.5 "Which workout was this?": a strength or mobility recording no planned session claims can
 * be tied to a workout, so muscle load uses that workout's exercises instead of a guess.
 */
data class WorkoutLinkUi(
    /** The recording is strength / mobility and no session of its day claims it. */
    val canLink: Boolean = false,
    /** The workout of the session already linked to it, if any. */
    val linkedWorkoutName: String? = null,
    val workouts: List<StrengthWorkout> = emptyList(),
    val showPicker: Boolean = false,
)

/** What the card shows for a recording of [sportType] given its day's [planned] sessions. */
fun workoutLinkUi(
    activityId: Long,
    sportType: SportType?,
    planned: List<PlannedSession>,
    workouts: List<StrengthWorkout>,
    showPicker: Boolean,
): WorkoutLinkUi {
    val linked = planned.firstOrNull { it.linkedActivityId == activityId }
    val eligible = sportType != null && (sportType.group == SportGroup.STRENGTH || sportType == SportType.MOBILITY)
    return WorkoutLinkUi(
        canLink = eligible && linked == null,
        linkedWorkoutName = linked?.workoutId?.let { id -> workouts.firstOrNull { it.id == id }?.name },
        workouts = workouts.sortedBy { it.name.lowercase() },
        showPicker = showPicker,
    )
}

@Composable
internal fun LinkedWorkoutCard(ui: WorkoutLinkUi, onPick: () -> Unit) {
    if (!ui.canLink && ui.linkedWorkoutName == null) return
    SectionCard(title = stringResource(Res.string.activity_workout_title)) {
        if (ui.linkedWorkoutName != null) {
            Text(stringResource(Res.string.session_workout_format, ui.linkedWorkoutName), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                stringResource(Res.string.activity_workout_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onPick, modifier = Modifier.testTag("activity_pick_workout")) {
                Text(stringResource(Res.string.activity_workout_pick))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutPickerSheet(workouts: List<StrengthWorkout>, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(workouts, key = { it.id }) { workout ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(workout.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = workout.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (workout.isVideo) "▶ ${workout.kind.label()}" else workout.kind.label(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
