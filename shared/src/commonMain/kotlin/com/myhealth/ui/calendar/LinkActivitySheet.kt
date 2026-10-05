package com.myhealth.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhealth.domain.engine.calendar.LinkProposal
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.ui.activities.formatDistanceKm
import com.myhealth.ui.activities.formatDuration
import com.myhealth.ui.common.displayName
import com.myhealth.ui.common.fmtInt
import kotlin.math.roundToInt

/** One row of the [LinkActivitySheet] list: a scored suggestion, or a plain manual-fallback row
 * when [confidence] is `null`. */
data class LinkCandidate(
    val activityId: Long,
    val title: String,
    val subtitle: String,
    val confidence: Double?,
)

/**
 * "82%" from a `[0,1]` confidence for display. Standard half-up rounding is fine here — unlike
 * `EventActivityLinker`'s 6-dp threshold rounding, nothing downstream compares this string to a
 * boundary.
 */
fun confidenceLabel(confidence: Double): String = "${fmtInt((confidence * 100).roundToInt())}%"

/**
 * Candidate rows for one event occurrence (PLAN §4.2 Day detail / P3.7): the linker's [proposals]
 * for this exact occurrence first, ordered by confidence descending, then the day's other
 * activities as a manual fallback (excluding any activity already listed as a proposal). Pure —
 * unit-tested in `LinkSheetStateTest`.
 */
fun linkCandidatesFor(
    occurrence: EventOccurrence,
    proposals: List<LinkProposal>,
    dayActivities: List<ActivitySummary>,
): List<LinkCandidate> {
    val ownProposals = proposals
        .filter {
            it.eventOccurrence.eventId == occurrence.eventId &&
                it.eventOccurrence.occurrenceDay == occurrence.occurrenceDay
        }
        .sortedWith(compareByDescending<LinkProposal> { it.confidence }.thenBy { it.activity.id })
    val proposedIds = ownProposals.map { it.activity.id }.toSet()

    val suggested = ownProposals.map { proposal ->
        LinkCandidate(
            activityId = proposal.activity.id,
            title = candidateTitle(proposal.activity),
            subtitle = candidateSubtitle(proposal.activity),
            confidence = proposal.confidence,
        )
    }
    val fallback = dayActivities
        .filter { it.id !in proposedIds }
        .sortedBy { it.startAtMillis }
        .map { activity ->
            LinkCandidate(
                activityId = activity.id,
                title = candidateTitle(activity),
                subtitle = candidateSubtitle(activity),
                confidence = null,
            )
        }
    return suggested + fallback
}

private fun candidateTitle(activity: ActivitySummary): String =
    activity.title?.takeIf { it.isNotBlank() } ?: activity.sportType.displayName()

private fun candidateSubtitle(activity: ActivitySummary): String = listOfNotNull(
    formatDuration(activity.durationSec),
    formatDistanceKm(activity.distanceMeters),
).joinToString(" · ")

/**
 * Bottom sheet for linking one event occurrence to an activity (PLAN §4.2 Day detail, P3.7):
 * [proposals] (with a confidence %) first, then the day's other activities as a manual fallback;
 * an "Unlink" row appears when [onUnlink] is non-null (i.e. the occurrence already carries a
 * link). Selecting a suggested row calls [onSelectSuggested] (`AUTO_ACCEPTED`); selecting a
 * fallback row calls [onSelectManual] (`MANUAL`) — the caller decides the [com.myhealth.domain.model.LinkMethod].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkActivitySheet(
    occurrence: EventOccurrence,
    proposals: List<LinkProposal>,
    dayActivities: List<ActivitySummary>,
    onSelectSuggested: (Long) -> Unit,
    onSelectManual: (Long) -> Unit,
    onUnlink: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState()
    val candidates = linkCandidatesFor(occurrence, proposals, dayActivities)
    val suggestedCount = candidates.count { it.confidence != null }
    val fallback = candidates.drop(suggestedCount)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    text = stringResource(Res.string.linksheet_title, occurrence.effectiveTitle),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (onUnlink != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onUnlink)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(stringResource(Res.string.linksheet_unlink_action), color = MaterialTheme.colorScheme.error)
                    }
                    HorizontalDivider()
                }
            }
            if (candidates.isEmpty()) {
                item {
                    Text(
                        stringResource(Res.string.linksheet_empty_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (suggestedCount > 0) {
                item { SheetSectionLabel(stringResource(Res.string.linksheet_section_suggested)) }
                items(candidates.take(suggestedCount), key = { "suggested-${it.activityId}" }) { candidate ->
                    LinkCandidateRow(candidate, onClick = { onSelectSuggested(candidate.activityId) })
                }
            }
            if (fallback.isNotEmpty()) {
                item { SheetSectionLabel(stringResource(Res.string.linksheet_section_more)) }
                items(fallback, key = { "manual-${it.activityId}" }) { candidate ->
                    LinkCandidateRow(candidate, onClick = { onSelectManual(candidate.activityId) })
                }
            }
        }
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun LinkCandidateRow(candidate: LinkCandidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(candidate.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(candidate.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        candidate.confidence?.let { Text(confidenceLabel(it), style = MaterialTheme.typography.labelLarge) }
    }
}
