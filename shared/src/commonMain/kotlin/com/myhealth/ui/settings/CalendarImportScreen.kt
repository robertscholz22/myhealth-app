package com.myhealth.ui.settings

import com.myhealth.ui.common.mathRound
import com.myhealth.ui.training.label
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.engine.suggest.SessionCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.CalendarChoice
import com.myhealth.di.CalendarGroup
import com.myhealth.di.CalendarImportOverview
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.calendar.ClusterKind
import com.myhealth.domain.model.CalendarClusterRule
import com.myhealth.domain.model.DeviceCalendar
import com.myhealth.domain.model.DeviceCalendarEntry
import com.myhealth.domain.model.EventType
import com.myhealth.resources.*
import com.myhealth.ui.calendar.displayName
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.LocalPlatformUi
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.stringResource
import com.myhealth.ui.common.usText
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What a group can be imported as. Races need a distance, notes no time: both stay manual. */
private val IMPORT_TYPES = listOf(
    EventType.SOCCER_TRAINING,
    EventType.SOCCER_MATCH,
    EventType.APPOINTMENT,
    EventType.BLOCKED,
    EventType.OTHER,
)

private const val MAX_DURATION_MIN = 600.0
private const val EXAMPLE_TITLES = 3

/**
 * Calendar import: choose calendars of this phone, see the groups MyHealth found in them (practice,
 * matches, …) and set per group whether and how its entries become MyHealth calendar events.
 */
@Composable
fun CalendarImportScreen(modifier: Modifier = Modifier) {
    val vm = rememberVm { g -> CalendarImportViewModel(g.calendarImport) }
    val state by vm.state.collectAsStateWithLifecycle()
    val requestAccess = LocalPlatformUi.current.rememberCalendarAccessRequest(vm::onAccessResult)
    // Coming back from the calendar app or the system settings: show what is there now.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    CalendarImportContent(
        state = state,
        onRequestAccess = requestAccess,
        onSelect = vm::setSelected,
        onRule = vm::setRule,
        onRestoreRemoved = vm::restoreRemoved,
        onCheckNow = vm::refresh,
        modifier = modifier,
    )
}

@Composable
private fun CalendarImportContent(
    state: CalendarImportUiState,
    onRequestAccess: () -> Unit,
    onSelect: (DeviceCalendar, Boolean) -> Unit,
    onRule: (CalendarGroup, CalendarClusterRule) -> Unit,
    onRestoreRemoved: () -> Unit,
    onCheckNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overview = state.overview
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(title = stringResource(Res.string.calendar_import_title)) {
                Text(stringResource(Res.string.calendar_import_intro), style = MaterialTheme.typography.bodyMedium)
                when {
                    state.isLoading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    !overview.supported ->
                        Text(stringResource(Res.string.calendar_import_unsupported), style = MaterialTheme.typography.bodyMedium)
                    !overview.hasAccess -> {
                        Text(
                            stringResource(
                                if (state.accessDenied) Res.string.calendar_import_access_denied else Res.string.calendar_import_access_needed,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.accessDenied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        Button(onClick = onRequestAccess) { Text(stringResource(Res.string.calendar_import_action_allow)) }
                    }
                }
                if (state.failed) {
                    Text(
                        stringResource(Res.string.calendar_import_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (state.isLoading || !overview.supported || !overview.hasAccess) return@LazyColumn

        item { CalendarsSection(overview.calendars, onSelect) }

        val byCalendar = overview.groups.groupBy { it.calendarName }
        val chosenNames = overview.calendars.filter { it.selected }.map { it.calendar.name }.distinct()
        items(chosenNames, key = { "groups-$it" }) { name ->
            GroupsSection(name, byCalendar[name].orEmpty(), onRule)
        }

        if (chosenNames.isNotEmpty() || overview.missingCalendars.isNotEmpty()) {
            item { StatusSection(overview, state.isWorking, onRestoreRemoved, onCheckNow) }
        }
    }
}

@Composable
private fun CalendarsSection(calendars: List<CalendarChoice>, onSelect: (DeviceCalendar, Boolean) -> Unit) {
    SectionCard(title = stringResource(Res.string.calendar_import_calendars_title)) {
        if (calendars.isEmpty()) {
            Text(stringResource(Res.string.calendar_import_calendars_empty), style = MaterialTheme.typography.bodyMedium)
        }
        calendars.forEach { choice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = choice.selected,
                        role = Role.Checkbox,
                        onValueChange = { onSelect(choice.calendar, it) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = choice.selected, onCheckedChange = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(choice.calendar.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(
                            Res.string.calendar_import_calendar_summary,
                            stringResource(
                                if (choice.calendar.isLocal) Res.string.calendar_import_calendar_local else Res.string.calendar_import_calendar_account,
                            ),
                            choice.upcoming,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupsSection(calendarName: String, groups: List<CalendarGroup>, onRule: (CalendarGroup, CalendarClusterRule) -> Unit) {
    SectionCard(title = calendarName) {
        if (groups.isEmpty()) {
            Text(stringResource(Res.string.calendar_import_groups_empty), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Text(
            stringResource(Res.string.calendar_import_groups_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        groups.forEachIndexed { index, group ->
            if (index > 0) HorizontalDivider()
            GroupBlock(group, onRule)
        }
    }
}

@Composable
private fun GroupBlock(group: CalendarGroup, onRule: (CalendarGroup, CalendarClusterRule) -> Unit) {
    val rule = group.rule
    val title = when (group.cluster.kind) {
        ClusterKind.FIXTURES -> stringResource(Res.string.calendar_import_group_fixtures, group.cluster.name)
        ClusterKind.REPEATED -> group.cluster.name
        ClusterKind.OTHER -> stringResource(Res.string.calendar_import_group_other)
    }
    val switchLabel = stringResource(Res.string.calendar_import_group_switch, title)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            val next = group.upcoming.firstOrNull()
            Text(
                if (next == null) {
                    stringResource(Res.string.calendar_import_group_none_upcoming)
                } else {
                    stringResource(Res.string.calendar_import_group_upcoming, group.upcoming.size, next.startText())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (group.cluster.kind != ClusterKind.REPEATED) {
                val examples = (group.upcoming.ifEmpty { group.cluster.entries })
                    .map { it.title.trim() }
                    .distinct()
                    .take(EXAMPLE_TITLES)
                if (examples.isNotEmpty()) {
                    Text(
                        stringResource(Res.string.calendar_import_group_examples, examples.joinToString(" · ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Switch(
            checked = rule.enabled,
            onCheckedChange = { onRule(group, rule.copy(enabled = it)) },
            modifier = Modifier.semantics { contentDescription = switchLabel },
        )
    }
    if (!rule.enabled) return

    DropdownField(
        label = stringResource(Res.string.calendar_import_rule_type),
        options = IMPORT_TYPES,
        selected = rule.type,
        optionLabel = { it.displayName() },
        onSelect = { type ->
            onRule(
                group,
                rule.copy(
                    type = type,
                    isKeyEvent = rule.isKeyEvent && type == EventType.SOCCER_MATCH,
                    // Each type starts at its own usual intensity.
                    intensity = rule.intensity.takeIf { type == rule.type },
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    val usual = group.cluster.entries.usualMinutes()
    val catalogEntry = SessionCatalog.forEvent(rule.type)
    if (catalogEntry != null) {
        DropdownField(
            label = stringResource(Res.string.calendar_import_rule_intensity),
            options = Intensity.entries,
            selected = rule.intensity ?: catalogEntry.intensity,
            optionLabel = { it.label() },
            onSelect = { chosen -> onRule(group, rule.copy(intensity = chosen.takeIf { it != catalogEntry.intensity })) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    NumberField(
        label = stringResource(Res.string.calendar_import_rule_duration),
        value = rule.durationMin?.toDouble(),
        onValueChange = { onRule(group, rule.copy(durationMin = it?.toInt()?.takeIf { minutes -> minutes > 0 })) },
        decimals = 0,
        min = 1.0,
        max = MAX_DURATION_MIN,
        supportingText = if (usual != null) {
            stringResource(Res.string.calendar_import_rule_duration_from_calendar, usual)
        } else {
            stringResource(Res.string.calendar_import_rule_duration_from_calendar_plain)
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (catalogEntry != null) {
        val minutes = rule.durationMin ?: usual ?: catalogEntry.defaultMin
        Text(
            stringResource(
                Res.string.calendar_import_rule_load,
                mathRound(catalogEntry.estTrimpFor(minutes, rule.intensity)).toInt(),
                minutes,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (rule.type == EventType.SOCCER_MATCH) {
        val keyLabel = stringResource(Res.string.calendar_import_rule_key)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = rule.isKeyEvent,
                    role = Role.Switch,
                    onValueChange = { onRule(group, rule.copy(isKeyEvent = it)) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(keyLabel, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(Res.string.calendar_import_rule_key_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = rule.isKeyEvent, onCheckedChange = null)
        }
    }
}

@Composable
private fun StatusSection(
    overview: CalendarImportOverview,
    isWorking: Boolean,
    onRestoreRemoved: () -> Unit,
    onCheckNow: () -> Unit,
) {
    SectionCard(title = stringResource(Res.string.calendar_import_status_title)) {
        Text(
            stringResource(Res.string.calendar_import_status_count, overview.importedUpcoming),
            style = MaterialTheme.typography.bodyMedium,
        )
        overview.lastCheckedAtMillis?.let { millis ->
            Text(
                stringResource(Res.string.calendar_import_status_checked, millis.dateTimeText()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (overview.missingCalendars.isNotEmpty()) {
            Text(
                stringResource(Res.string.calendar_import_status_missing, overview.missingCalendars.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (overview.editedInApp > 0) {
            Text(
                stringResource(Res.string.calendar_import_status_edited, overview.editedInApp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (overview.removedInApp > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.calendar_import_status_removed, overview.removedInApp),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRestoreRemoved) { Text(stringResource(Res.string.calendar_import_action_restore)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCheckNow, enabled = !isWorking) {
                Text(stringResource(Res.string.calendar_import_action_check))
            }
            if (isWorking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

private fun DeviceCalendarEntry.startText(): String {
    val zone = if (allDay) TimeZone.UTC else TimeZone.currentSystemDefault()
    val start = Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(zone)
    return if (allDay) start.date.usText("EEE d MMM") else start.usText("EEE d MMM HH:mm")
}

private fun Long.dateTimeText(): String =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault()).usText("d MMM HH:mm")

/** The most common length of the timed entries, in minutes. */
private fun List<DeviceCalendarEntry>.usualMinutes(): Int? =
    filterNot { it.allDay }
        .map { ((it.endMillis - it.startMillis) / 60_000L).toInt() }
        .filter { it > 0 }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
