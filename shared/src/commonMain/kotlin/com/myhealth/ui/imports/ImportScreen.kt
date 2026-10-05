package com.myhealth.ui.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.rememberDocumentInfo
import com.myhealth.ui.common.rememberDocumentOpener
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.ImportKind
import com.myhealth.sync.ImportWorkState
import com.myhealth.ui.common.ErrorBanner
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.theme.MyHealthTheme

/** MIME filter for the document picker (§4.2): Garmin exports arrive under all of these. */
private val IMPORT_MIME_TYPES = listOf(
    "application/zip",
    "text/csv",
    "text/comma-separated-values",
    "application/octet-stream",
    "*/*",
)

@Composable
fun ImportScreen(modifier: Modifier = Modifier) {
    val vm = rememberVm { g ->
        ImportViewModel(g.importRepo, g.importService, g.syncScheduler, g.pendingImportUri)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val sharedUri by vm.sharedUri.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val documentInfo = rememberDocumentInfo()

    val picker = rememberDocumentOpener(persistReadAccess = true) { uri ->
        if (uri != null) {
            val info = documentInfo(uri)
            vm.start(uri, info.displayName, info.mimeType)
        }
    }

    // A `.fit`/`.csv`/`.zip` shared into the app starts importing as soon as this screen opens.
    LaunchedEffect(sharedUri) {
        val uri = sharedUri ?: return@LaunchedEffect
        val info = documentInfo(uri)
        vm.start(uri, info.displayName, info.mimeType)
        vm.clearSharedUri()
    }

    // The undo result (or its failure) is reported once, then cleared.
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.resolveText())
            vm.consumeMessage()
        }
    }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { innerPadding ->
        ImportContent(
            state = state,
            onPickFile = { picker(IMPORT_MIME_TYPES) },
            onImportAnyway = vm::importAnyway,
            onDismissResult = vm::dismissResult,
            onUndo = vm::undo,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

@Composable
private fun ImportContent(
    state: ImportUiState,
    onPickFile: () -> Unit,
    onImportAnyway: () -> Unit,
    onDismissResult: () -> Unit,
    onUndo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PickSection(state, onPickFile) }
        state.unsupportedFile?.let { name ->
            item {
                ErrorBanner(message = stringResource(Res.string.import_unsupported_file_format, name))
            }
        }
        item { StatusSection(state, onImportAnyway, onDismissResult) }
        item { HistorySection(state.history, onUndo) }
    }
}

@Composable
private fun PickSection(state: ImportUiState, onPickFile: () -> Unit) {
    SectionCard(title = stringResource(Res.string.import_section_title)) {
        Text(
            stringResource(Res.string.import_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onPickFile, enabled = !state.isRunning, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(Res.string.import_action_pick_file))
        }
    }
}

@Composable
private fun StatusSection(
    state: ImportUiState,
    onImportAnyway: () -> Unit,
    onDismissResult: () -> Unit,
) {
    when (state.work.stage) {
        ImportWorkState.Stage.IDLE -> Unit
        ImportWorkState.Stage.RUNNING -> SectionCard(title = stringResource(Res.string.import_status_running_title)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            CountsRow(state.work)
            state.work.currentItem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ImportWorkState.Stage.DONE -> SectionCard(title = stringResource(Res.string.import_status_done_title)) {
            CountsRow(state.work)
            OutlinedButton(onClick = onDismissResult, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(Res.string.import_action_done))
            }
        }
        ImportWorkState.Stage.ALREADY_IMPORTED -> SectionCard(title = stringResource(Res.string.import_status_already_imported_title)) {
            Text(
                state.work.message?.let { stringResource(Res.string.import_already_imported_message_format, it) }
                    ?: stringResource(Res.string.import_already_imported_message_plain),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.canForce) {
                    Button(onClick = onImportAnyway) { Text(stringResource(Res.string.import_action_import_anyway)) }
                }
                OutlinedButton(onClick = onDismissResult) { Text(stringResource(Res.string.import_action_dismiss)) }
            }
        }
        ImportWorkState.Stage.FAILED -> ErrorBanner(
            message = state.work.message ?: stringResource(Res.string.import_error_failed_fallback),
            onRetry = onDismissResult,
        )
    }
}

@Composable
private fun CountsRow(work: ImportWorkState) {
    Text(
        stringResource(
            Res.string.import_counts_format,
            work.parsed,
            work.inserted,
            work.duplicate,
            work.failed,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}


@Preview(showBackground = true)
@Composable
private fun ImportContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        ImportContent(
            state = ImportUiState(
                work = ImportWorkState(
                    stage = ImportWorkState.Stage.DONE,
                    parsed = 412,
                    inserted = 380,
                    duplicate = 30,
                    failed = 2,
                ),
                history = listOf(
                    ImportHistoryItem(
                        id = 1,
                        fileName = "garmin-export.zip",
                        kind = ImportKind.GARMIN_ZIP,
                        importedAtMillis = 1_778_396_400_000L,
                        parsed = 412,
                        inserted = 380,
                        duplicate = 30,
                        errorCount = 2,
                    ),
                ),
            ),
            onPickFile = {},
            onImportAnyway = {},
            onDismissResult = {},
            onUndo = {},
        )
    }
}
