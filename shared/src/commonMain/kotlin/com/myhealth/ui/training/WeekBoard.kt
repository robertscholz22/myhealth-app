package com.myhealth.ui.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.myhealth.resources.*
import com.myhealth.ui.common.mathRound
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.ui.activities.formatDistanceKm
import com.myhealth.ui.activities.formatDuration
import com.myhealth.ui.calendar.displayName
import com.myhealth.ui.calendar.formatMinuteOfDay
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.SportIcon
import com.myhealth.ui.common.displayName
import com.myhealth.ui.strength.workoutPrescriptionLine
import com.myhealth.ui.theme.MyHealthTheme

/**
 * The seven day rows of one plan week (PLAN §4.2 "Training plan"): the fixed calendar events the
 * plan has to work around, the planned sessions themselves, and what was actually recorded.
 */
@Composable
fun WeekDayRow(
    row: TrainingDayRow,
    isSelected: Boolean,
    actions: PlannedSessionActions,
    onSelectDay: (Long) -> Unit,
    onAddSession: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** `workoutId -> StrengthWorkout` (P14.7/P16.2) — the source of `PlannedSessionCard`'s
     * `workoutName` and `workoutExercisesLine`. */
    workouts: Map<Long, StrengthWorkout> = emptyMap(),
) {
    SectionCard(
        title = dayHeaderLabel(row.day),
        modifier = modifier.clickable { onSelectDay(row.day) },
        action = {
            if (row.isToday || isSelected) {
                Text(
                    text = if (row.isToday) {
                        stringResource(Res.string.training_today_badge)
                    } else {
                        stringResource(Res.string.training_selected_badge)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    ) {
        row.events.forEach { FixedEventRow(it) }
        row.planned.forEach { session ->
            PlannedSessionCard(
                session = session,
                actions = actions,
                workoutName = session.workoutId?.let { workouts[it]?.name },
                isMobilityWorkout = session.workoutId?.let { workouts[it]?.kind?.isMobility } ?: false,
                workoutExercisesLine = session.workoutId?.let { workouts[it] }?.let { workoutPrescriptionLine(it) },
            )
        }
        row.activities.forEach { CompletedActivityRow(it, onOpenActivity) }
        if (row.isEmpty) {
            Text(
                text = stringResource(Res.string.training_rest_day),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        TextButton(onClick = { onAddSession(row.day) }) { Text(stringResource(Res.string.training_add_session)) }
    }
}

/** A match, a soccer training or a race: fixed, not movable by the suggester. */
@Composable
private fun FixedEventRow(event: EventOccurrence) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = eventIcon(event.type),
            contentDescription = event.type.displayName(),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.effectiveTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    event.type.displayName(),
                    formatMinuteOfDay(event.effectiveStartMinuteOfDay) ?: stringResource(Res.string.training_all_day),
                    event.effectiveDurationMin?.let { "$it min" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun eventIcon(type: EventType): ImageVector = when (type) {
    EventType.RACE -> Icons.Filled.EmojiEvents
    else -> Icons.Filled.SportsSoccer
}

/** What actually happened, compact — the plan's feedback loop. */
@Composable
private fun CompletedActivityRow(activity: ActivitySummary, onOpenActivity: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpenActivity(activity.id) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SportIcon(
            activity.sportGroup,
            modifier = Modifier.size(20.dp).padding(end = 0.dp),
        )
        Text(
            text = listOfNotNull(
                activity.title?.takeIf { it.isNotBlank() } ?: activity.sportType.displayName(),
                formatDuration(activity.durationSec),
                formatDistanceKm(activity.distanceMeters),
                activity.trimp?.let { "${mathRound(it)} AU" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WeekDayRowPreview() {
    MyHealthTheme(dynamicColor = false) {
        WeekDayRow(
            row = TrainingDayRow(
                day = 20_710L,
                isToday = true,
                events = emptyList(),
                planned = listOf(
                    previewSession(),
                    previewSession(id = 2L, status = PlannedStatus.COMPLETED, locked = true),
                ),
                activities = emptyList(),
            ),
            isSelected = true,
            actions = PlannedSessionActions(),
            onSelectDay = {},
            onAddSession = {},
            onOpenActivity = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
