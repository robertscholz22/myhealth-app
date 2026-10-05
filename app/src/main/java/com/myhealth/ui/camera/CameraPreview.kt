package com.myhealth.ui.camera

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The CameraX viewfinder of the scan screen (PLAN P4.8): a `PreviewView` inside an `AndroidView`,
 * bound to the lifecycle with `CameraSelector.DEFAULT_BACK_CAMERA` and exactly the use cases the
 * current [mode] needs — `Preview` + [imageCapture] for LABEL, `Preview` + [imageAnalysis] for
 * BARCODE. Binding only the use case in play keeps the analyser (and its ML Kit calls) off the
 * critical path while the user lines up a label.
 *
 * The [imageCapture] and [imageAnalysis] instances are owned by the caller so the shutter button
 * can trigger a capture; [rememberImageCapture] and [rememberImageAnalysis] build them.
 */
@Composable
fun CameraPreview(
    imageCapture: ImageCapture,
    imageAnalysis: ImageAnalysis,
    mode: ScanMode,
    torchOn: Boolean,
    analysisEnabled: Boolean,
    onFrame: (ImageProxy) -> Unit,
    onBindError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analyzerExecutor = rememberSingleThreadExecutor()
    val frameHandler by rememberUpdatedState(onFrame)
    val bindErrorHandler by rememberUpdatedState(onBindError)
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(mode, previewView) {
        val provider = try {
            ProcessCameraProvider.getInstance(context).awaitValue()
        } catch (e: Exception) {
            bindErrorHandler(e)
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build()
            .apply { setSurfaceProvider(previewView.surfaceProvider) }
        val useCases: Array<UseCase> = when (mode) {
            ScanMode.LABEL -> arrayOf(preview, imageCapture)
            ScanMode.BARCODE -> arrayOf(preview, imageAnalysis)
        }
        camera = try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
        } catch (e: Exception) {
            bindErrorHandler(e)
            null
        }
    }

    LaunchedEffect(camera, torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    LaunchedEffect(mode, analysisEnabled, analyzerExecutor) {
        if (mode == ScanMode.BARCODE && analysisEnabled) {
            imageAnalysis.setAnalyzer(analyzerExecutor) { frame -> frameHandler(frame) }
        } else {
            imageAnalysis.clearAnalyzer()
        }
    }

    DisposableEffect(imageAnalysis) {
        onDispose { imageAnalysis.clearAnalyzer() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Single-shot capture, latency-optimised: the user is holding a phone over a package. */
@Composable
fun rememberImageCapture(): ImageCapture = remember {
    ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
}

/**
 * Continuous analysis with `STRATEGY_KEEP_ONLY_LATEST` (P4.8): ML Kit is slower than the sensor,
 * so dropping stale frames is what keeps the preview fluid instead of the queue growing.
 */
@Composable
fun rememberImageAnalysis(): ImageAnalysis = remember {
    ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
}

@Composable
private fun rememberSingleThreadExecutor(): ExecutorService {
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(executor) {
        onDispose { executor.shutdown() }
    }
    return executor
}

/**
 * Awaits a CameraX `ListenableFuture` without `kotlinx-coroutines-guava` (not in the catalog,
 * R4/R5) — one listener, and cancellation forwarded to the future.
 */
private suspend fun <T> ListenableFuture<T>.awaitValue(): T = suspendCancellableCoroutine { cont ->
    addListener(
        {
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        },
        Executor { runnable -> runnable.run() },
    )
    cont.invokeOnCancellation { cancel(false) }
}
