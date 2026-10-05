package com.myhealth.ui.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.formatDeviceDateTime
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.ImportKind
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.SectionCard
import kotlinx.datetime.TimeZone

private const val TIMESTAMP_FORMAT = "d MMM yyyy, HH:mm"

/**
 * The `import_record` history (§4.2 "Import"). Every row carries an overflow menu whose only
 * entry is "Undo import": it removes the source records that import wrote, re-merging or deleting
 * the canonical activities, and forgets the file's checksum so it can be imported again.
 */
@Composable
internal fun HistorySection(history: List<ImportHistoryItem>, onUndo: (Long) -> Unit) {
    SectionCard(title = stringResource(Res.string.import_history_title)) {
        if (history.isEmpty()) {
            EmptyState(
                title = stringResource(Res.string.import_history_empty_title),
                message = stringResource(Res.string.import_history_empty_message),
            )
            return@SectionCard
        }
        history.forEach { item -> HistoryRow(item, onUndo) }
    }
}

@Composable
private fun HistoryRow(item: ImportHistoryItem, onUndo: (Long) -> Unit) {
    var confirming by remember(item.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.fileName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.summary(), style = MaterialTheme.typography.bodySmall)
        }
        RowMenu(item, onUndoClicked = { confirming = true })
    }

    if (confirming) {
        UndoDialog(
            item = item,
            onConfirm = {
                confirming = false
                onUndo(item.id)
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun RowMenu(item: ImportHistoryItem, onUndoClicked: () -> Unit) {
    var expanded by remember(item.id) { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(Res.string.import_history_row_menu_cd, item.fileName),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.import_action_undo)) },
                onClick = {
                    expanded = false
                    onUndoClicked()
                },
            )
        }
    }
}

@Composable
private fun UndoDialog(item: ImportHistoryItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.import_undo_dialog_title)) },
        text = {
            Text(stringResource(Res.string.import_undo_dialog_message_format, item.inserted, item.fileName))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.import_action_undo)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

@Composable
private fun ImportHistoryItem.summary(): String = if (errorCount > 0) {
    stringResource(
        Res.string.import_history_summary_with_errors_format,
        formatTimestamp(importedAtMillis),
        kind.label(),
        parsed,
        inserted,
        duplicate,
        errorCount,
    )
} else {
    stringResource(
        Res.string.import_history_summary_format,
        formatTimestamp(importedAtMillis),
        kind.label(),
        parsed,
        inserted,
        duplicate,
    )
}

@Composable
private fun ImportKind.label(): String = when (this) {
    ImportKind.FIT_FILE -> stringResource(Res.string.import_kind_fit_file)
    ImportKind.GARMIN_CSV -> stringResource(Res.string.import_kind_garmin_csv)
    ImportKind.GARMIN_ZIP -> stringResource(Res.string.import_kind_garmin_export)
    ImportKind.JSON_BACKUP -> stringResource(Res.string.import_kind_json_backup)
}

private fun formatTimestamp(millis: Long): String =
    formatDeviceDateTime(millis, TimeZone.currentSystemDefault(), TIMESTAMP_FORMAT)
