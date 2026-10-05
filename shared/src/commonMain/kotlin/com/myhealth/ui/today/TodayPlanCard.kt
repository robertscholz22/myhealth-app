package com.myhealth.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.mathRound
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.ui.calendar.displayName
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.SportIcon
import com.myhealth.ui.theme.MyHealthTheme
import com.myhealth.ui.training.TargetZoneChip
import com.myhealth.ui.training.WorkoutStructureSection
import com.myhealth.ui.training.formatPaceSecPerKm
import com.myhealth.ui.training.label
import com.myhealth.ui.training.StaleSuggestionsHint
import com.myhealth.ui.training.plannedSessionSubtitle

/**
 * "Today's plan" (PLAN §4.2 Today (Home), section 2), in the three states the day can be in:
 *
 * 1. **Planned sessions exist** — each one with its sport, type, intensity, duration and status,
 *    plus the single action that matters on the day itself: mark it done.
 * 2. **Nothing planned but a proposal is waiting** — the best-scoring suggestion for today from
 *    the latest `PROPOSED` batch, with its leading rationale line and a way into the review.
 * 3. **Neither** — an invitation to generate a week, which opens the training screen.
 *
 * On top of any of the three, [isStale] (POLISH-8) says the open `PROPOSED` batch was generated
 * before the calendar changed: the card then leads with a "Calendar changed — regenerate" hint
 * that opens the training screen, where the suggester actually runs.
 */
@Composable
fun TodayPlanCard(
    planned: List<PlannedSession>,
    suggested: SuggestedSession?,
    isStale: Boolean = false,
    hrZoneModel: HrZoneModel? = null,
    onMarkDone: (Long) -> Unit,
    onReviewSuggestions: () -> Unit,
    onOpenTraining: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(Res.string.today_plan_title),
        modifier = modifier,
        action = { TextButton(onClick = onOpenTraining) { Text(stringResource(Res.string.today_plan_action)) } },
    ) {
        if (isStale) StaleSuggestionsHint(onRegenerate = onOpenTraining)
        when {
            planned.isNotEmpty() -> planned.forEach { PlannedRow(it, hrZoneModel, onMarkDone) }
            suggested != null -> SuggestedRow(suggested, hrZoneModel, onReviewSuggestions)
            else -> {
                Text(
                    text = stringResource(Res.string.today_plan_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onOpenTraining) {
                    Text(stringResource(Res.string.training_generate_suggestions))
                }
            }
        }
    }
}

@Composable
private fun PlannedRow(session: PlannedSession, hrZoneModel: HrZoneModel?, onMarkDone: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SportIcon(session.sportType.group, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = session.sessionType.displayName(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AssistChip(onClick = {}, enabled = false, label = { Text(session.intensity.label()) })
            }
            Text(
                text = plannedSessionSubtitle(session, linkedLabel = stringResource(Res.string.training_session_linked)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TargetZoneChip(session.sessionType, hrZoneModel)
            WorkoutStructureSection(session.structureJson)
        }
        if (session.status == PlannedStatus.PLANNED) {
            TextButton(onClick = { onMarkDone(session.id) }) { Text(stringResource(Res.string.today_mark_done)) }
        }
    }
}

@Composable
private fun SuggestedRow(session: SuggestedSession, hrZoneModel: HrZoneModel?, onReviewSuggestions: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SportIcon(session.sportType.group, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = session.sessionType.displayName(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AssistChip(onClick = {}, enabled = false, label = { Text(session.intensity.label()) })
            }
            Text(
                text = listOfNotNull(
                    stringResource(Res.string.today_suggested_label),
                    session.targetDurationMin?.let { "$it min" },
                    session.targetPaceSecPerKm?.let { formatPaceSecPerKm(it) },
                    "${mathRound(session.estimatedTrimp)} AU",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TargetZoneChip(session.sessionType, hrZoneModel)
            WorkoutStructureSection(session.structureJson)
            session.rationale.firstOrNull()?.let { entry ->
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onReviewSuggestions) { Text(stringResource(Res.string.today_review_action)) }
    }
}

@Preview(showBackground = true)
@Composable
private fun TodayPlanCardPreview() {
    MyHealthTheme(dynamicColor = false) {
        TodayPlanCard(
            planned = listOf(
                PlannedSession(
                    id = 1L,
                    planId = 1L,
                    day = 20_710L,
                    startMinuteOfDay = null,
                    sportType = SportType.RUN_OUTDOOR,
                    sessionType = SessionType.TEMPO_RUN,
                    intensity = Intensity.HIGH,
                    targetDurationMin = 50,
                    targetDistanceMeters = 10_000.0,
                    targetPaceSecPerKm = 285,
                    estimatedTrimp = 105.0,
                    description = null,
                    rationale = null,
                    status = PlannedStatus.PLANNED,
                    locked = false,
                    linkedActivityId = null,
                    sourceSuggestionId = null,
                    createdAtMillis = 0L,
                    updatedAtMillis = 0L,
                ),
            ),
            suggested = null,
            onMarkDone = {},
            onReviewSuggestions = {},
            onOpenTraining = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
