package com.myhealth.ui.share

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.share.RemoteStatus
import com.myhealth.domain.share.ShareKind
import com.myhealth.domain.util.AppError
import com.myhealth.resources.*
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.rememberTextSharer
import com.myhealth.ui.common.stringResource
import org.jetbrains.compose.resources.StringResource

/**
 * P27: More › Family sharing — create or join a family library, then browse it: import what
 * others shared, update imported items, upload changes to mine.
 */
@Composable
fun ShareLibraryScreen(modifier: Modifier = Modifier) {
    val vm = rememberVm { g -> ShareLibraryViewModel(g.shareRepo) }
    val state by vm.state.collectAsStateWithLifecycle()
    val share = rememberTextSharer()
    Column(modifier.fillMaxSize()) {
        if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("share_working"))
        state.message?.let { MessageBar(it, vm::dismissMessage) }
        when {
            !state.loaded -> Unit
            state.setup == null -> NotConnected(state, vm, share)
            else -> Connected(state, vm, share)
        }
    }
}

@Composable
private fun MessageBar(message: ShareMessage, onDismiss: () -> Unit) {
    val text = when (message) {
        is ShareMessage.Failed -> errorText(message.error)
        is ShareMessage.Imported -> stringResource(Res.string.share_imported_count, message.result.imported, message.result.updated) +
            if (message.result.problems.isNotEmpty()) "\n" + stringResource(Res.string.share_missing_dependency) else ""
        is ShareMessage.Uploaded -> stringResource(Res.string.share_uploaded_count, message.count)
        ShareMessage.InvalidCode -> stringResource(Res.string.share_code_invalid)
        ShareMessage.InvalidProjectId -> stringResource(Res.string.share_project_id_invalid)
    }
    val error = message is ShareMessage.Failed || message is ShareMessage.InvalidCode || message is ShareMessage.InvalidProjectId
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("share_message"),
    ) {
        Row(Modifier.padding(horizontal = SCREEN_PADDING, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

/** What went wrong, in the user's terms: the rules, sign-in, the connection, or anything else. */
@Composable
fun errorText(error: AppError): String = when {
    error is AppError.Network && error.code == 403 -> stringResource(Res.string.share_error_rules)
    error is AppError.Network && error.code == 400 -> stringResource(Res.string.share_error_sign_in)
    error is AppError.Network && error.code == null -> stringResource(Res.string.share_error_offline)
    error is AppError.Validation -> error.message
    error is AppError.Parse -> error.detail
    else -> stringResource(Res.string.share_error_generic, error.toString().take(120))
}

@Composable
private fun NotConnected(state: ShareUiState, vm: ShareLibraryViewModel, share: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.share_intro), style = MaterialTheme.typography.bodyMedium)
        when (state.mode) {
            ShareMode.CHOOSE -> {
                Button(onClick = { vm.setMode(ShareMode.JOIN) }, Modifier.fillMaxWidth().testTag("share_join")) {
                    Text(stringResource(Res.string.share_action_join))
                }
                OutlinedButton(onClick = { vm.setMode(ShareMode.CREATE) }, Modifier.fillMaxWidth().testTag("share_create")) {
                    Text(stringResource(Res.string.share_action_create))
                }
            }
            ShareMode.JOIN -> SectionCard(stringResource(Res.string.share_action_join)) {
                NameField(state, vm)
                OutlinedTextField(
                    value = state.codeText,
                    onValueChange = vm::setCodeText,
                    label = { Text(stringResource(Res.string.share_code_label)) },
                    supportingText = { Text(stringResource(Res.string.share_code_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("share_code_field"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.setMode(ShareMode.CHOOSE) }) { Text(stringResource(Res.string.action_cancel)) }
                    Button(
                        onClick = vm::join,
                        enabled = !state.working && state.name.isNotBlank() && state.codeText.isNotBlank(),
                        modifier = Modifier.testTag("share_connect"),
                    ) { Text(stringResource(Res.string.share_action_connect)) }
                }
            }
            ShareMode.CREATE -> CreateWizard(state, vm, share)
        }
    }
}

@Composable
private fun NameField(state: ShareUiState, vm: ShareLibraryViewModel) {
    OutlinedTextField(
        value = state.name,
        onValueChange = vm::setName,
        label = { Text(stringResource(Res.string.share_name_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag("share_name_field"),
    )
}

@Composable
private fun CreateWizard(state: ShareUiState, vm: ShareLibraryViewModel, share: (String) -> Unit) {
    SectionCard(stringResource(Res.string.share_create_title)) {
        Text(stringResource(Res.string.share_create_intro), style = MaterialTheme.typography.bodyMedium)
        listOf(
            Res.string.share_create_step1, Res.string.share_create_step2, Res.string.share_create_step3,
            Res.string.share_create_step4, Res.string.share_create_step5,
        ).forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}.", style = MaterialTheme.typography.titleSmall)
                Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(Res.string.share_create_rules), style = MaterialTheme.typography.titleSmall)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Text(
                state.rules,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()).padding(8.dp).testTag("share_rules"),
            )
        }
        OutlinedButton(onClick = { share(state.rules) }, modifier = Modifier.testTag("share_rules_share")) {
            Text(stringResource(Res.string.share_action_share_rules))
        }
        OutlinedTextField(
            value = state.projectId, onValueChange = vm::setProjectId, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            label = { Text(stringResource(Res.string.share_project_id_label)) },
            modifier = Modifier.fillMaxWidth().testTag("share_project_field"),
        )
        OutlinedTextField(
            value = state.apiKey, onValueChange = vm::setApiKey, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            label = { Text(stringResource(Res.string.share_api_key_label)) },
            modifier = Modifier.fillMaxWidth().testTag("share_key_field"),
        )
        NameField(state, vm)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.setMode(ShareMode.CHOOSE) }) { Text(stringResource(Res.string.action_cancel)) }
            Button(
                onClick = vm::create,
                enabled = !state.working && state.name.isNotBlank() && state.projectId.isNotBlank() && state.apiKey.isNotBlank(),
                modifier = Modifier.testTag("share_create_connect"),
            ) { Text(stringResource(Res.string.share_action_connect)) }
        }
    }
}

@Composable
private fun Connected(state: ShareUiState, vm: ShareLibraryViewModel, share: (String) -> Unit) {
    val setup = state.setup ?: return
    var confirmLeave by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<LibraryRow?>(null) }
    val codeMessage = stringResource(Res.string.share_code_message, setup.code)
    LazyColumn(Modifier.fillMaxSize().testTag("share_list")) {
        item {
            Column(Modifier.padding(SCREEN_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionCard(stringResource(Res.string.share_connected_as, setup.displayName)) {
                    Text(stringResource(Res.string.share_connected_project, setup.projectId), style = MaterialTheme.typography.bodyMedium)
                    state.library?.members?.takeIf { it.isNotEmpty() }?.let { members ->
                        Text(
                            stringResource(Res.string.share_members, members.sortedBy { it.joinedAtMillis }.joinToString { it.name }),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { share(codeMessage) }, modifier = Modifier.testTag("share_send_code")) {
                            Text(stringResource(Res.string.share_action_send_code))
                        }
                        TextButton(onClick = { renaming = true }) { Text(stringResource(Res.string.share_action_rename)) }
                        TextButton(onClick = { confirmLeave = true }, modifier = Modifier.testTag("share_leave")) {
                            Text(stringResource(Res.string.share_action_leave))
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::refresh, enabled = !state.working, modifier = Modifier.testTag("share_refresh")) {
                        Text(stringResource(Res.string.share_action_refresh))
                    }
                    if (state.updatableCount > 0) {
                        Button(onClick = vm::updateAll, enabled = !state.working, modifier = Modifier.testTag("share_update_all")) {
                            Text(stringResource(Res.string.share_action_update_all, state.updatableCount))
                        }
                    }
                    if (state.changedCount > 0) {
                        Button(onClick = vm::uploadChanges, enabled = !state.working, modifier = Modifier.testTag("share_upload_changes")) {
                            Text(stringResource(Res.string.share_action_upload_changes, state.changedCount))
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.filter == null, onClick = { vm.setFilter(null) }, label = { Text(stringResource(Res.string.share_filter_all)) })
                    ShareKind.entries.forEach { kind ->
                        FilterChip(
                            selected = state.filter == kind,
                            onClick = { vm.setFilter(kind) },
                            label = { Text(stringResource(kind.label())) },
                            modifier = Modifier.testTag("share_filter_${kind.name}"),
                        )
                    }
                }
                val authors = state.authors
                if (authors.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.activeAuthor == null,
                            onClick = { vm.setAuthor(null) },
                            label = { Text(stringResource(Res.string.share_filter_everyone)) },
                        )
                        authors.forEach { (uid, name) ->
                            FilterChip(
                                selected = state.activeAuthor == uid,
                                onClick = { vm.setAuthor(if (state.activeAuthor == uid) null else uid) },
                                label = { Text(if (uid == setup.uid) stringResource(Res.string.share_filter_me) else name) },
                                modifier = Modifier.testTag("share_author_$name"),
                            )
                        }
                    }
                }
                if ((state.library?.items?.size ?: 0) > SEARCH_FROM) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = vm::setQuery,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.share_search)) },
                        modifier = Modifier.fillMaxWidth().testTag("share_search"),
                    )
                }
            }
        }
        if (state.library != null && state.rows.isEmpty()) {
            item { Text(stringResource(Res.string.share_library_empty), Modifier.padding(SCREEN_PADDING)) }
        }
        items(state.rows, key = { it.item.shareId }) { row ->
            ListItem(
                headlineContent = { Text(row.item.name) },
                supportingContent = {
                    Text(stringResource(row.item.kind.label()) + " · " + stringResource(Res.string.share_by, row.item.authorName))
                },
                trailingContent = { StatusChip(row.status) { preview = row } },
                modifier = Modifier.testTag("share_row_${row.item.name}"),
            )
            HorizontalDivider()
        }
    }
    preview?.let { row ->
        PreviewDialog(
            row = row,
            onDismiss = { preview = null },
            onImport = { vm.import(row.item.shareId); preview = null },
            onRemove = { vm.removeFromLibrary(row.item.shareId); preview = null },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            text = { Text(stringResource(Res.string.share_leave_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.leave() }, modifier = Modifier.testTag("share_leave_confirm")) {
                    Text(stringResource(Res.string.share_action_leave))
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            text = { NameField(state, vm) },
            confirmButton = { TextButton(onClick = { renaming = false; vm.rename() }) { Text(stringResource(Res.string.action_save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun StatusChip(status: RemoteStatus, onClick: () -> Unit) {
    val label = when (status) {
        RemoteStatus.YOURS -> Res.string.share_status_yours
        RemoteStatus.NOT_IMPORTED -> Res.string.share_status_import
        RemoteStatus.UP_TO_DATE -> Res.string.share_status_imported
        RemoteStatus.UPDATE_AVAILABLE -> Res.string.share_status_update
    }
    AssistChip(onClick = onClick, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("share_status_${status.name}"))
}

@Composable
private fun PreviewDialog(row: LibraryRow, onDismiss: () -> Unit, onImport: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(row.item.kind.label()) + " · " + stringResource(Res.string.share_by, row.item.authorName))
                previewLines(row).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            when (row.status) {
                RemoteStatus.NOT_IMPORTED -> TextButton(onClick = onImport, modifier = Modifier.testTag("share_import")) {
                    Text(stringResource(Res.string.share_action_import))
                }
                RemoteStatus.UPDATE_AVAILABLE -> TextButton(onClick = onImport, modifier = Modifier.testTag("share_import")) {
                    Text(stringResource(Res.string.share_action_update))
                }
                RemoteStatus.YOURS -> TextButton(onClick = onRemove, modifier = Modifier.testTag("share_remove")) {
                    Text(stringResource(Res.string.share_action_remove_from_library))
                }
                RemoteStatus.UP_TO_DATE -> TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** A few facts about the item before importing it. */
@Composable
private fun previewLines(row: LibraryRow): List<String> {
    val payload = row.item.payload
    return when (row.item.kind) {
        ShareKind.EXERCISE -> com.myhealth.domain.share.SharePayloads.decodeExercise(payload)?.let { p ->
            listOf(stringResource(Res.string.share_preview_muscles, (p.primary + p.secondary).joinToString { it.lowercase().replace('_', ' ') }))
        }
        ShareKind.INGREDIENT -> com.myhealth.domain.share.SharePayloads.decodeIngredient(payload)?.let { p ->
            val per = when (p.basis) { "PER_100ML" -> "100 ml"; "PER_PIECE" -> "1"; else -> "100 g" }
            listOfNotNull(stringResource(Res.string.share_preview_kcal, p.kcal.toInt().toString(), per), p.brand)
        }
        ShareKind.MEAL -> com.myhealth.domain.share.SharePayloads.decodeMeal(payload)?.let { p ->
            listOfNotNull(stringResource(Res.string.share_preview_items, p.items.size, p.servings.toString().removeSuffix(".0")), p.note)
        }
        ShareKind.WORKOUT -> com.myhealth.domain.share.SharePayloads.decodeWorkout(payload)?.let { p ->
            listOfNotNull(stringResource(Res.string.share_preview_rows, p.rows.size), p.videoTitle)
        }
    }.orEmpty()
}

fun ShareKind.label(): StringResource = when (this) {
    ShareKind.EXERCISE -> Res.string.share_kind_exercise
    ShareKind.INGREDIENT -> Res.string.share_kind_ingredient
    ShareKind.MEAL -> Res.string.share_kind_meal
    ShareKind.WORKOUT -> Res.string.share_kind_workout
}

/** The search field appears once the library is longer than a screen. */
private const val SEARCH_FROM = 8
