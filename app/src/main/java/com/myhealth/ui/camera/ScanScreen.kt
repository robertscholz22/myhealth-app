package com.myhealth.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.AppGraph
import com.myhealth.di.rememberVm
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.ErrorBanner
import com.myhealth.ui.theme.MyHealthTheme
import java.util.concurrent.Executor

/**
 * The camera screen (PLAN §4.2 "Scan (camera)", P4.8): a CameraX viewfinder with a LABEL/BARCODE
 * segmented toggle and a torch button. LABEL takes one picture, recognises it and goes to the OCR
 * review screen; BARCODE reads continuously and, on the first product code, looks it up on Open
 * Food Facts and opens the ingredient editor.
 *
 * The `CAMERA` permission is requested here — a `ViewModel` cannot launch an
 * `ActivityResultContract`; a refusal ends in an [EmptyState] that deep-links to app settings,
 * since a second in-app request is never shown once the user has said no twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onReview: () -> Unit,
    onIngredient: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val graphVm = rememberVm { graph ->
        // The camera sources and cacheDir are Android-only, so they live on AppGraph, not UiGraph.
        val android = graph as AppGraph
        ScanViewModel(android.scanSources, android.offLookup, android.draftStore, android.cacheDir)
    }
    val state by graphVm.state.collectAsStateWithLifecycle()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { result ->
        granted = result
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // "Scan from photo" (NOTE-6): the photo picker needs no permission at all, so this path also
    // works when the camera was denied — and it is the only OCR path on emulators whose Play
    // Services never finish downloading the unbundled ML Kit model.
    val photoLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        graphVm.onPhotoPicked(uri)
    }
    val pickPhoto = { photoLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }

    LaunchedEffect(state.nav) {
        when (val nav = state.nav) {
            null -> Unit
            ScanNav.Review -> {
                graphVm.consumeNav()
                onReview()
            }
            is ScanNav.Ingredient -> {
                graphVm.consumeNav()
                onIngredient(nav.barcode)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = pickPhoto, enabled = !state.isProcessing) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = stringResource(Res.string.scan_from_photo_content_description))
                    }
                    IconButton(onClick = graphVm::toggleTorch, enabled = granted) {
                        Icon(
                            imageVector = if (state.torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = stringResource(if (state.torchOn) Res.string.scan_torch_off else Res.string.scan_torch_on),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (granted) {
                ScanViewfinder(
                    state = state,
                    onMode = graphVm::setMode,
                    onPickPhoto = pickPhoto,
                    onCaptured = graphVm::onLabelCaptured,
                    onFrame = graphVm::onAnalyzerFrame,
                    onCaptureFailed = graphVm::onCaptureFailed,
                    onManual = graphVm::enterManually,
                    onDismissError = graphVm::dismissError,
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    CameraDenied(
                        showSettings = asked,
                        onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onSettings = { context.openAppSettings() },
                        modifier = Modifier.weight(1f),
                    )
                    FromPhotoButton(
                        onClick = pickPhoto,
                        enabled = !state.isProcessing,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanViewfinder(
    state: ScanUiState,
    onMode: (ScanMode) -> Unit,
    onPickPhoto: () -> Unit,
    onCaptured: (ImageProxy) -> Unit,
    onFrame: (ImageProxy) -> Unit,
    onCaptureFailed: (Throwable) -> Unit,
    onManual: () -> Unit,
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current
    val imageCapture = rememberImageCapture()
    val imageAnalysis = rememberImageAnalysis()
    val mainExecutor: Executor = remember(context) { ContextCompat.getMainExecutor(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CameraPreview(
                imageCapture = imageCapture,
                imageAnalysis = imageAnalysis,
                mode = state.mode,
                torchOn = state.torchOn,
                analysisEnabled = state.analysisActive && state.lastError == null,
                onFrame = onFrame,
                onBindError = onCaptureFailed,
                modifier = Modifier.fillMaxSize(),
            )
            if (state.isProcessing) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.lastError?.let { message ->
                ErrorBanner(message = message, onRetry = onDismissError)
                if (state.manualBarcode != null) {
                    TextButton(onClick = onManual) { Text(stringResource(Res.string.scan_enter_manually_action)) }
                }
            }
            ModeToggle(mode = state.mode, onMode = onMode)
            Text(
                text = stringResource(
                    when (state.mode) {
                        ScanMode.LABEL -> Res.string.scan_hint_label
                        ScanMode.BARCODE -> Res.string.scan_hint_barcode
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FromPhotoButton(onClick = onPickPhoto, enabled = !state.isProcessing)
                if (state.mode == ScanMode.LABEL) {
                    Button(
                        onClick = {
                            imageCapture.takePicture(
                                mainExecutor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) = onCaptured(image)

                                    override fun onError(exception: ImageCaptureException) =
                                        onCaptureFailed(exception)
                                },
                            )
                        },
                        enabled = !state.isProcessing,
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null)
                        Text(stringResource(Res.string.scan_capture_label_action))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeToggle(mode: ScanMode, onMode: (ScanMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ScanMode.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = mode == entry,
                onClick = { onMode(entry) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ScanMode.entries.size),
            ) {
                Text(stringResource(if (entry == ScanMode.LABEL) Res.string.scan_mode_label else Res.string.scan_mode_barcode))
            }
        }
    }
}

/** The "From photo" action, offered in both modes and with or without the camera permission. */
@Composable
private fun FromPhotoButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
        Text(stringResource(Res.string.scan_from_photo_action))
    }
}

@Composable
private fun CameraDenied(
    showSettings: Boolean,
    onRequest: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = stringResource(Res.string.scan_permission_denied_title),
        message = stringResource(
            if (showSettings) Res.string.scan_permission_denied_message_settings else Res.string.scan_permission_denied_message_allow,
        ),
        icon = Icons.Filled.NoPhotography,
        actionLabel = stringResource(if (showSettings) Res.string.scan_open_settings_action else Res.string.scan_allow_camera_action),
        onAction = if (showSettings) onSettings else onRequest,
        modifier = modifier.fillMaxWidth().padding(top = 48.dp),
    )
}

/** `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` deep link for a permanently denied permission. */
private fun android.content.Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 760, name = "Camera denied")
@Composable
private fun ScanDeniedPreview() {
    MyHealthTheme(dynamicColor = false) {
        CameraDenied(showSettings = true, onRequest = {}, onSettings = {})
    }
}
