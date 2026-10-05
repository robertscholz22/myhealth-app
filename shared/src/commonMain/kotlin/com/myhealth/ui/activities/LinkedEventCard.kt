package com.myhealth.ui.activities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.myhealth.ui.common.SectionCard

/**
 * "Linked event" card + its "Link to event…" picker sheet (PLAN §4.2 Activity detail, P3.7),
 * split out of `ActivityDetailScreen.kt` to keep both files under the R10 400-line guideline.
 */

/** Title-cased `EventType` label — a small local copy of `ui/calendar`'s `labelOf`/`displayName`
 * (internal to that package, so not visible from `ui/activities`). */
private fun EventType.label(): String =
    name.split("_").joinToString(" ") { it.lowercase() }.replaceFirstChar(Char::uppercase)

@Composable
internal fun LinkedEventCard(linkedEvent: EventOccurrence?, onOpenPicker: () -> Unit, onUnlink: () -> Unit) {
    SectionCard(title = stringResource(Res.string.activity_linked_event_title)) {
        if (linkedEvent == null) {
            Text(stringResource(Res.string.activity_linked_event_none), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenPicker) { Text(stringResource(Res.string.activity_linked_event_link_action)) }
        } else {
            Text(linkedEvent.effectiveTitle, style = MaterialTheme.typography.bodyLarge)
            Text(
                linkedEvent.type.label(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenPicker) { Text(stringResource(Res.string.activity_linked_event_change_action)) }
                TextButton(onClick = onUnlink) { Text(stringResource(Res.string.activity_linked_event_unlink_action)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventPickerSheet(events: List<EventOccurrence>, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (events.isEmpty()) {
            Text(
                stringResource(Res.string.activity_event_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(events, key = { it.eventId }) { occurrence ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(occurrence.eventId) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = occurrence.effectiveTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(occurrence.type.label(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
