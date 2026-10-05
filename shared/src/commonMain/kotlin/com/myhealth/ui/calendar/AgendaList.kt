package com.myhealth.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.ui.activities.formatDistanceKm
import com.myhealth.ui.activities.formatDuration
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.SportIcon
import com.myhealth.ui.common.displayName
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

/** Title-cased enum label, e.g. `SOCCER_MATCH` -> "Soccer match". */
internal fun labelOf(name: String): String =
    name.split("_").joinToString(" ") { it.lowercase() }.replaceFirstChar(Char::uppercase)

internal fun EventType.displayName(): String = labelOf(name)

internal fun SessionType.displayName(): String = labelOf(name)

internal fun PlannedStatus.displayName(): String = labelOf(name)

internal fun MealSlot.displayName(): String = labelOf(name)

/**
 * The agenda of one day (P3.4, week mode): events with their time, planned sessions with status,
 * completed activities and a per-slot meal summary — everything [CalendarDay] carries that has a
 * place in a one-line row.
 */
@Composable
fun AgendaList(
    date: LocalDate,
    day: CalendarDay?,
    onOpenDay: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = day?.events.orEmpty()
    val planned = day?.planned.orEmpty()
    val activities = day?.activities.orEmpty()
    val meals = day?.meals.orEmpty()
    val isEmpty = events.isEmpty() && planned.isEmpty() && activities.isEmpty() && meals.isEmpty()

    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 88.dp)) {
        item(key = "agenda-header") {
            AgendaHeader(date = date, onOpenDay = { onOpenDay(date.toEpochDays()) })
        }
        if (isEmpty) {
            item(key = "agenda-empty") {
                EmptyState(
                    title = stringResource(Res.string.agenda_empty_title),
                    message = stringResource(Res.string.agenda_empty_message),
                )
            }
        }
        items(events, key = { "event-${it.eventId}-${it.occurrenceDay}" }) { occurrence ->
            AgendaRow(
                lead = formatMinuteOfDay(occurrence.effectiveStartMinuteOfDay) ?: stringResource(Res.string.agenda_all_day),
                title = occurrence.effectiveTitle,
                subtitle = occurrence.type.displayName(),
            )
        }
        items(planned, key = { "planned-${it.id}" }) { session -> PlannedAgendaRow(session) }
        items(activities, key = { "activity-${it.id}" }) { activity ->
            ActivityAgendaRow(activity = activity, onClick = { onOpenActivity(activity.id) })
        }
        if (meals.isNotEmpty()) {
            item(key = "meals") {
                AgendaRow(
                    lead = stringResource(Res.string.agenda_meals_lead),
                    title = stringResource(Res.string.agenda_meals_logged_count, meals.size),
                    subtitle = stringResource(Res.string.agenda_meals_kcal, day?.intake?.kcal ?: 0.0),
                )
            }
        }
    }
}

@Composable
private fun AgendaHeader(date: LocalDate, onOpenDay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = fullDateTitle(date), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onOpenDay) { Text(stringResource(Res.string.agenda_day_detail_action)) }
    }
}

@Composable
private fun PlannedAgendaRow(session: PlannedSession) {
    AgendaRow(
        lead = formatMinuteOfDay(session.startMinuteOfDay) ?: stringResource(Res.string.agenda_planned_lead),
        title = session.sessionType.displayName(),
        subtitle = "${session.sportType.displayName()} · ${session.status.displayName()}",
    )
}

@Composable
private fun ActivityAgendaRow(activity: ActivitySummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SportIcon(activity.sportGroup)
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
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun AgendaRow(lead: String, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = lead,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(64.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun AgendaListPreview() {
    val date = LocalDate(2026, 9, 14)
    MyHealthTheme(dynamicColor = false) {
        AgendaList(
            date = date,
            day = previewCalendarDay(date.toEpochDays()),
            onOpenDay = {},
            onOpenActivity = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 380, name = "Empty")
@Composable
private fun AgendaListEmptyPreview() {
    MyHealthTheme(dynamicColor = false) {
        AgendaList(date = LocalDate(2026, 9, 15), day = null, onOpenDay = {}, onOpenActivity = {})
    }
}
