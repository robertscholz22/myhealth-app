package com.myhealth.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.mathRound
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealLogSummary
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SleepRecord
import com.myhealth.ui.activities.formatDistanceKm
import com.myhealth.ui.activities.formatDuration
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.SourceBadgeRow
import com.myhealth.ui.common.SportIcon
import com.myhealth.ui.common.displayName
import com.myhealth.ui.common.fmtDecimal
import com.myhealth.ui.common.resolve
import com.myhealth.ui.cycle.dayOfCycleLabel
import com.myhealth.ui.cycle.phaseLabelRes

/** The per-item overflow menu shared by the event and planned-session rows (§4.2 Day detail). */
@Composable
internal fun OverflowMenu(actions: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.daydetail_overflow_more_actions_desc))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
internal fun EventsSection(
    events: List<EventOccurrence>,
    activities: List<ActivitySummary>,
    onEdit: (EventOccurrence) -> Unit,
    onDelete: (Long) -> Unit,
    onLink: (EventOccurrence) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.daydetail_section_events)) {
        if (events.isEmpty()) {
            EmptyLine(stringResource(Res.string.daydetail_empty_events))
            return@SectionCard
        }
        events.forEach { occurrence ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistChip(onClick = {}, label = { Text(occurrence.type.displayName()) })
                        Text(
                            text = formatMinuteOfDay(occurrence.effectiveStartMinuteOfDay)
                                ?: stringResource(Res.string.daydetail_all_day),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Text(
                        text = occurrence.effectiveTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = eventSubtitle(occurrence, activities),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val isLinked = occurrence.linkedActivityId != null
                OverflowMenu(
                    listOf(
                        stringResource(Res.string.daydetail_action_edit) to { onEdit(occurrence) },
                        (
                            if (isLinked) {
                                stringResource(Res.string.daydetail_action_manage_link)
                            } else {
                                stringResource(Res.string.daydetail_action_link_activity)
                            }
                            ) to { onLink(occurrence) },
                        stringResource(Res.string.action_delete) to { onDelete(occurrence.eventId) },
                    ),
                )
            }
        }
    }
}

@Composable
private fun eventSubtitle(
    occurrence: EventOccurrence,
    activities: List<ActivitySummary>,
): String = buildList {
    occurrence.effectiveDurationMin?.let { add("$it min") }
    occurrence.sportType?.let { add(it.displayName()) }
    occurrence.linkedActivityId?.let { add(linkedActivityLabel(it, activities).resolve()) }
    if (occurrence.isKeyEvent) add(stringResource(Res.string.daydetail_event_key_event))
    if (occurrence.isOverride) add(stringResource(Res.string.daydetail_event_modified_occurrence))
}.joinToString(" · ").ifEmpty { stringResource(Res.string.daydetail_event_no_details) }

@Composable
internal fun PlannedSection(
    sessions: List<PlannedSession>,
    onSetStatus: (Long, PlannedStatus) -> Unit,
    onEdit: (Long) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.daydetail_section_planned)) {
        if (sessions.isEmpty()) {
            EmptyLine(stringResource(Res.string.daydetail_empty_planned))
            return@SectionCard
        }
        sessions.forEach { session ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SportIcon(session.sportType.group, modifier = Modifier.padding(end = 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = session.sessionType.displayName(), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = plannedSubtitle(session),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OverflowMenu(
                    listOf(
                        stringResource(Res.string.daydetail_action_mark_done) to {
                            onSetStatus(session.id, PlannedStatus.COMPLETED)
                        },
                        stringResource(Res.string.daydetail_action_skip) to {
                            onSetStatus(session.id, PlannedStatus.SKIPPED)
                        },
                        stringResource(Res.string.daydetail_action_edit) to { onEdit(session.id) },
                    ),
                )
            }
        }
    }
}

private fun plannedSubtitle(session: PlannedSession): String = buildList {
    add(session.sportType.displayName())
    add(session.status.displayName())
    session.targetDurationMin?.let { add("$it min") }
    formatDistanceKm(session.targetDistanceMeters)?.let { add(it) }
}.joinToString(" · ")

@Composable
internal fun ActivitiesSection(activities: List<ActivitySummary>, onOpenActivity: (Long) -> Unit) {
    SectionCard(title = stringResource(Res.string.daydetail_section_activities)) {
        if (activities.isEmpty()) {
            EmptyLine(stringResource(Res.string.daydetail_empty_activities))
            return@SectionCard
        }
        activities.forEach { activity ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenActivity(activity.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SportIcon(activity.sportGroup, modifier = Modifier.padding(end = 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activity.title?.takeIf { it.isNotBlank() } ?: activity.sportType.displayName(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            formatDuration(activity.durationSec),
                            formatDistanceKm(activity.distanceMeters),
                            activity.avgHr?.let { "$it bpm" },
                            activity.trimp?.let { stringResource(Res.string.daydetail_activity_trimp, mathRound(it)) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (activity.mergedSources.isNotEmpty()) {
                        SourceBadgeRow(activity.mergedSources, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun MealsSection(meals: List<MealLogSummary>, intake: MacroTotals, onOpenNutrition: () -> Unit) {
    SectionCard(title = stringResource(Res.string.daydetail_section_meals)) {
        if (meals.isEmpty()) {
            EmptyLine(stringResource(Res.string.daydetail_empty_meals))
        } else {
            meals.forEach { meal ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenNutrition() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = meal.name?.takeIf { it.isNotBlank() } ?: meal.slot.displayName(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(Res.string.daydetail_meal_kcal_protein, meal.totals.kcal, meal.totals.proteinG),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                text = stringResource(Res.string.daydetail_meal_day_total, intake.kcal, intake.proteinG),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
internal fun SleepSection(sleep: SleepRecord?) {
    SectionCard(title = stringResource(Res.string.daydetail_section_sleep)) {
        if (sleep == null) {
            EmptyLine(stringResource(Res.string.daydetail_empty_sleep))
            return@SectionCard
        }
        Text(text = formatSleepDuration(sleep.totalSleepMin), style = MaterialTheme.typography.titleLarge)
        val stages = listOfNotNull(
            sleep.deepMin?.let { stringResource(Res.string.daydetail_sleep_stage_deep, it) },
            sleep.remMin?.let { stringResource(Res.string.daydetail_sleep_stage_rem, it) },
            sleep.lightMin?.let { stringResource(Res.string.daydetail_sleep_stage_light, it) },
            sleep.awakeMin?.let { stringResource(Res.string.daydetail_sleep_stage_awake, it) },
        )
        if (stages.isNotEmpty()) {
            Text(
                text = stages.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sleep.sleepScore?.let {
            Text(text = stringResource(Res.string.daydetail_sleep_score, it), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun TargetsSection(target: NutritionTarget?, intake: MacroTotals, onOpenNutrition: () -> Unit) {
    SectionCard(title = stringResource(Res.string.daydetail_section_targets)) {
        val rows = targetProgressRows(target, intake)
        if (rows.isEmpty()) {
            EmptyLine(stringResource(Res.string.daydetail_empty_targets))
            return@SectionCard
        }
        Text(
            text = target?.dayType?.let { labelOf(it.name) }.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        rows.forEach { row ->
            Column(modifier = Modifier.fillMaxWidth().clickable { onOpenNutrition() }) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = row.label, style = MaterialTheme.typography.bodyMedium)
                    Text(text = row.valueLabel, style = MaterialTheme.typography.bodySmall)
                }
                LinearProgressIndicator(
                    progress = { row.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun LoadSection(load: DailyLoad?) {
    SectionCard(title = stringResource(Res.string.daydetail_section_load)) {
        if (load == null) {
            EmptyLine(stringResource(Res.string.daydetail_empty_load))
            return@SectionCard
        }
        Text(
            text = stringResource(
                Res.string.daydetail_load_trimp_acwr,
                load.trimp,
                load.acwr?.let { fmtDecimal(it, 2) } ?: "—",
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        val recovery = listOfNotNull(
            load.recoveryScore?.let { stringResource(Res.string.daydetail_load_recovery, it) },
            load.recoveryBand?.let { labelOf(it.name) },
        )
        if (recovery.isNotEmpty()) {
            Text(
                text = recovery.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (load.flags.isNotEmpty()) {
            Text(text = load.flags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The "Cycle" line (PLAN §5 P11.3): phase + day of cycle, "(predicted)" when the day is beyond
 * the last logged cycle. Shown only while tracking is enabled. */
@Composable
internal fun CycleSection(status: CycleStatus?) {
    SectionCard(title = stringResource(Res.string.daydetail_section_cycle)) {
        if (status == null) {
            EmptyLine(stringResource(Res.string.cycle_history_empty))
            return@SectionCard
        }
        Text(
            text = stringResource(phaseLabelRes(status.phase)) +
                " · " + dayOfCycleLabel(status.dayOfCycle, status.cycleLengthDays) +
                if (status.isPredicted) stringResource(Res.string.daydetail_cycle_predicted_suffix) else "",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
