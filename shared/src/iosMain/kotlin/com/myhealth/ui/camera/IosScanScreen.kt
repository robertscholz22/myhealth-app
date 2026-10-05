package com.myhealth.ui.camera

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.platform.IosCamera
import com.myhealth.platform.IosDocuments
import com.myhealth.platform.IosPhotos
import com.myhealth.resources.*
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.ErrorBanner
import com.myhealth.ui.common.stringResource
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/** Camera state of the scan screen: not decided yet, refused, no camera at all, or running. */
private enum class CameraAccess { ASKING, DENIED, NONE, READY }

/**
 * The scan screen on iOS (P22.3), the same layout as Android's: a live preview with the label /
 * barcode toggle, a shutter for labels, and "From photo" — which is also the whole screen on a
 * device without a camera (the simulator) or with camera access turned off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosScanScreen(onBack: () -> Unit, onReview: () -> Unit, onIngredient: (String) -> Unit) {
    val vm = rememberVm { graph -> IosScanViewModel(graph.offLookup, graph.draftStore, IosDocuments.cacheDir("Scans")) }
    val state by vm.state.collectAsStateWithLifecycle()

    var access by remember {
        mutableStateOf(
            when {
                !IosCamera.isPresent -> CameraAccess.NONE
                IosCamera.isAuthorized -> CameraAccess.READY
                else -> CameraAccess.ASKING
            },
        )
    }
    LaunchedEffect(Unit) {
        if (access == CameraAccess.ASKING) access = if (IosCamera.requestAccess()) CameraAccess.READY else CameraAccess.DENIED
    }
    val camera = remember { IosCamera(onBarcode = vm::onBarcode) }
    DisposableEffect(access) {
        if (access == CameraAccess.READY && !camera.start()) access = CameraAccess.NONE
        onDispose { camera.stop() }
    }
    camera.barcodeEnabled = state.isBarcodeMode && state.analysisActive && state.lastError == null
    LaunchedEffect(state.torchOn) { camera.setTorch(state.torchOn) }

    val pickPhoto = {
        vm.onPhotoStarted()
        IosPhotos.pick(vm::onPhotoPicked)
    }

    LaunchedEffect(state.nav) {
        when (val nav = state.nav) {
            null -> Unit
            ScanNav.Review -> {
                vm.consumeNav()
                onReview()
            }
            is ScanNav.Ingredient -> {
                vm.consumeNav()
                onIngredient(nav.barcode)
            }
        }
    }

    Scaffold(
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
                    IconButton(onClick = vm::toggleTorch, enabled = access == CameraAccess.READY) {
                        Icon(
                            imageVector = if (state.torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = stringResource(if (state.torchOn) Res.string.scan_torch_off else Res.string.scan_torch_on),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (access) {
                    CameraAccess.READY -> UIKitView(factory = { camera.view }, modifier = Modifier.fillMaxSize())
                    CameraAccess.ASKING -> Unit
                    CameraAccess.DENIED -> EmptyState(
                        title = stringResource(Res.string.scan_permission_denied_title),
                        message = stringResource(Res.string.scan_permission_denied_message_settings_ios),
                        icon = Icons.Filled.NoPhotography,
                        actionLabel = stringResource(Res.string.scan_open_settings_action),
                        onAction = ::openAppSettings,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                    CameraAccess.NONE -> EmptyState(
                        title = stringResource(Res.string.scan_no_camera_title),
                        message = stringResource(Res.string.scan_no_camera_message),
                        icon = Icons.Filled.NoPhotography,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
                if (state.isProcessing) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.lastError?.let { message ->
                    ErrorBanner(message = message, onRetry = vm::dismissError)
                    if (state.manualBarcode != null) {
                        TextButton(onClick = vm::enterManually) { Text(stringResource(Res.string.scan_enter_manually_action)) }
                    }
                }
                ModeToggle(mode = state.mode, onMode = vm::setMode)
                Text(
                    text = stringResource(if (state.isBarcodeMode) Res.string.scan_hint_barcode else Res.string.scan_hint_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = pickPhoto, enabled = !state.isProcessing) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                        Text(stringResource(Res.string.scan_from_photo_action))
                    }
                    if (!state.isBarcodeMode && access == CameraAccess.READY) {
                        Button(
                            onClick = { vm.onCapture(camera::capture) },
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

private fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}
