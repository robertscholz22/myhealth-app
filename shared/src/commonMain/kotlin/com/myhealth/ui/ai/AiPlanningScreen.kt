package com.myhealth.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.ai.AiDataGroup
import com.myhealth.domain.repository.AiChange
import com.myhealth.domain.repository.AiChangeResult
import com.myhealth.domain.util.AppError
import com.myhealth.resources.*
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.rememberTextSharer
import com.myhealth.ui.common.stringResource
import com.myhealth.ui.common.usText
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** P28.5: More › AI planning — connect to the relay, pair Claude / ChatGPT, see and undo the AI's changes. */
@Composable
fun AiPlanningScreen(modifier: Modifier = Modifier) {
    val vm = rememberVm { g -> AiPlanningViewModel(g.aiPlanning) }
    val state by vm.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ai_working"))
        state.message?.let { MessageBar(it, vm::dismissMessage) }
        when {
            !state.loaded -> Unit
            state.setup == null -> NotConnected(state, vm)
            else -> Connected(state, vm)
        }
    }
    if (state.confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { vm.askDisconnect(false) },
            title = { Text(stringResource(Res.string.ai_disconnect_title)) },
            text = { Text(stringResource(Res.string.ai_disconnect_body)) },
            confirmButton = {
                TextButton(onClick = vm::disconnect, modifier = Modifier.testTag("ai_disconnect_confirm")) {
                    Text(stringResource(Res.string.ai_disconnect))
                }
            },
            dismissButton = { TextButton(onClick = { vm.askDisconnect(false) }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun MessageBar(message: AiMessage, onDismiss: () -> Unit) {
    val text = when (message) {
        is AiMessage.Failed -> errorText(message.error)
        is AiMessage.Synced -> if (message.applied + message.rejected == 0) {
            stringResource(Res.string.ai_synced_nothing)
        } else {
            stringResource(Res.string.ai_synced, message.applied, message.rejected)
        }
        is AiMessage.Undone -> if (message.count == 0) stringResource(Res.string.ai_undo_nothing) else stringResource(Res.string.ai_undone_count, message.count)
        AiMessage.InvalidCode -> stringResource(Res.string.ai_code_invalid)
    }
    val error = message is AiMessage.Failed || message is AiMessage.InvalidCode
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("ai_message"),
    ) {
        Row(Modifier.padding(horizontal = SCREEN_PADDING, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun errorText(error: AppError): String = when {
    error is AppError.Network && error.code == 401 -> stringResource(Res.string.ai_error_key)
    error is AppError.Network && error.code == null -> stringResource(Res.string.share_error_offline)
    error is AppError.Network -> error.cause?.message ?: stringResource(Res.string.share_error_generic, "HTTP ${error.code}")
    error is AppError.Validation -> error.message
    error is AppError.Parse -> error.detail
    else -> stringResource(Res.string.share_error_generic, error.toString().take(120))
}

@Composable
private fun NotConnected(state: AiUiState, vm: AiPlanningViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.ai_intro), style = MaterialTheme.typography.bodyMedium)
        GroupsCard(state, vm)
        OutlinedTextField(
            value = state.codeText,
            onValueChange = vm::setCodeText,
            label = { Text(stringResource(Res.string.ai_code_label)) },
            supportingText = { Text(stringResource(Res.string.ai_code_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("ai_code_field"),
        )
        Button(
            onClick = vm::connect,
            enabled = !state.working && state.codeText.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("ai_connect"),
        ) { Text(stringResource(Res.string.ai_connect)) }
    }
}

@Composable
private fun Connected(state: AiUiState, vm: AiPlanningViewModel) {
    val share = rememberTextSharer()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val setup = state.setup!!
        SectionCard(stringResource(Res.string.ai_status_title)) {
            Text(
                setup.lastSyncAtMillis?.let { stringResource(Res.string.ai_last_sync, dateTime(it)) } ?: stringResource(Res.string.ai_never_synced),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("ai_last_sync"),
            )
            setup.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ai_last_error"))
            }
            state.relay?.let { relay ->
                Text(
                    if (relay.connectors.isEmpty()) stringResource(Res.string.ai_not_paired) else stringResource(Res.string.ai_paired_with, relay.connectors.joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ai_paired"),
                )
            }
            Button(onClick = { vm.sync() }, enabled = !state.working, modifier = Modifier.testTag("ai_sync")) {
                Text(stringResource(Res.string.ai_sync_now))
            }
        }

        SectionCard(stringResource(Res.string.ai_pair_title)) {
            val pairing = state.pairing
            if (pairing == null) {
                OutlinedButton(onClick = vm::showPairingCode, enabled = !state.working, modifier = Modifier.testTag("ai_pair")) {
                    Text(stringResource(Res.string.ai_pair_action))
                }
            } else {
                SelectionContainer {
                    Text(
                        pairing.code.chunked(4).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("ai_pair_code"),
                    )
                }
                Text(stringResource(Res.string.ai_pair_expires, time(pairing.expiresAtMillis)), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(Res.string.ai_pair_steps), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.ai_mcp_address), style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(state.mcpUrl.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai_mcp_url"))
            }
            TextButton(onClick = { share(state.mcpUrl.orEmpty()) }) { Text(stringResource(Res.string.ai_share_address)) }
        }

        GroupsCard(state, vm)

        SectionCard(stringResource(Res.string.ai_changes_title)) {
            if (state.batches.isEmpty()) {
                Text(stringResource(Res.string.ai_changes_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.batches.forEachIndexed { i, batch ->
                if (i > 0) HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dateTime(batch.atMillis), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (batch.canUndo) {
                        TextButton(onClick = { vm.undo(batch.batchId) }, enabled = !state.working, modifier = Modifier.testTag("ai_undo_${batch.batchId}")) {
                            Text(stringResource(Res.string.ai_undo))
                        }
                    }
                }
                batch.changes.forEach { ChangeRow(it) }
            }
        }

        OutlinedButton(onClick = { vm.askDisconnect(true) }, modifier = Modifier.fillMaxWidth().testTag("ai_disconnect")) {
            Text(stringResource(Res.string.ai_disconnect))
        }
    }
}

@Composable
private fun ChangeRow(change: AiChange) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("ai_change")) {
        Text(
            change.summary,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (change.result == AiChangeResult.UNDONE) TextDecoration.LineThrough else null,
            color = if (change.result == AiChangeResult.APPLIED) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (change.result) {
            AiChangeResult.REJECTED -> Text(
                stringResource(Res.string.ai_result_rejected, change.reason.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            AiChangeResult.UNDONE -> Text(stringResource(Res.string.ai_result_undone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AiChangeResult.APPLIED -> Unit
        }
    }
}

@Composable
private fun GroupsCard(state: AiUiState, vm: AiPlanningViewModel) {
    SectionCard(stringResource(Res.string.ai_groups_title)) {
        AiDataGroup.entries.forEach { group ->
            val (title, desc) = when (group) {
                AiDataGroup.BASICS -> Res.string.ai_group_basics to Res.string.ai_group_basics_desc
                AiDataGroup.LOAD -> Res.string.ai_group_load to Res.string.ai_group_load_desc
                AiDataGroup.BODY -> Res.string.ai_group_body to Res.string.ai_group_body_desc
                AiDataGroup.CYCLE -> Res.string.ai_group_cycle to Res.string.ai_group_cycle_desc
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = group == AiDataGroup.BASICS || group in state.groups,
                    onCheckedChange = { vm.toggleGroup(group) },
                    enabled = group != AiDataGroup.BASICS && !state.working,
                    modifier = Modifier.testTag("ai_group_${group.name.lowercase()}"),
                )
            }
        }
    }
}

private fun dateTime(millis: Long): String =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).usText("yyyy-MM-dd HH:mm")

private fun time(millis: Long): String =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).usText("HH:mm")
