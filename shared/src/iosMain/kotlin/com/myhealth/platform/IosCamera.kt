package com.myhealth.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeEAN13Code
import platform.AVFoundation.AVMetadataObjectTypeEAN8Code
import platform.AVFoundation.AVMetadataObjectTypeUPCECode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.fileDataRepresentation
import platform.AVFoundation.hasTorch
import platform.AVFoundation.requestAccessForMediaType
import platform.AVFoundation.setTorchMode
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.UIKit.UIImage
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * The camera for the scan screen (P22.3): one `AVCaptureSession` with a preview, a photo output
 * for nutrition labels and a metadata output that reports EAN/UPC codes as they come into view
 * (AVFoundation's own barcode reader, no frame analysis needed). The simulator has no camera:
 * [start] then reports `false` and the screen offers "From photo" only.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCamera(private val onBarcode: (String) -> Unit) {

    private val session = AVCaptureSession()
    private val photoOutput = AVCapturePhotoOutput()
    private val metadataOutput = AVCaptureMetadataOutput()
    private var device: AVCaptureDevice? = null
    private val metadataDelegate = MetadataDelegate { code -> if (barcodeEnabled) onBarcode(code) }
    private var photoDelegate: PhotoDelegate? = null

    /** Codes are only reported in barcode mode and while no lookup is running. */
    var barcodeEnabled: Boolean = false

    val view: UIView = PreviewView(AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    })

    /** Wires the session; `false` when there is no usable camera. */
    fun start(): Boolean {
        val camera = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return false
        val input = AVCaptureDeviceInput.deviceInputWithDevice(camera, error = null) ?: return false
        session.beginConfiguration()
        session.sessionPreset = AVCaptureSessionPresetPhoto
        if (session.canAddInput(input)) session.addInput(input)
        if (session.canAddOutput(photoOutput)) session.addOutput(photoOutput)
        if (session.canAddOutput(metadataOutput)) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(metadataDelegate, dispatch_get_main_queue())
            metadataOutput.metadataObjectTypes = listOf(
                AVMetadataObjectTypeEAN13Code,
                AVMetadataObjectTypeEAN8Code,
                AVMetadataObjectTypeUPCECode,
            ).filter { it in metadataOutput.availableMetadataObjectTypes }
        }
        session.commitConfiguration()
        device = camera
        // startRunning blocks until the camera is up, so not on the main thread.
        dispatch_async(dispatch_get_global_queue(0, 0u)) { session.startRunning() }
        return true
    }

    fun stop() {
        dispatch_async(dispatch_get_global_queue(0, 0u)) { session.stopRunning() }
    }

    fun setTorch(on: Boolean) {
        val camera = device ?: return
        if (!camera.hasTorch) return
        if (camera.lockForConfiguration(null)) {
            camera.setTorchMode(if (on) AVCaptureTorchModeOn else AVCaptureTorchModeOff)
            camera.unlockForConfiguration()
        }
    }

    /** One photo for the label path; `null` when the capture failed. */
    suspend fun capture(): UIImage? = suspendCancellableCoroutine { cont ->
        val delegate = PhotoDelegate { image ->
            photoDelegate = null
            cont.resume(image)
        }
        photoDelegate = delegate
        photoOutput.capturePhotoWithSettings(AVCapturePhotoSettings.photoSettings(), delegate)
    }

    private class PreviewView(private val preview: AVCaptureVideoPreviewLayer) : UIView(frame = CGRectZero.readValue()) {
        init {
            layer.addSublayer(preview)
        }

        override fun layoutSubviews() {
            super.layoutSubviews()
            preview.frame = bounds
        }
    }

    private class MetadataDelegate(private val onCode: (String) -> Unit) :
        NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
        override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
            didOutputMetadataObjects.filterIsInstance<AVMetadataMachineReadableCodeObject>()
                .firstNotNullOfOrNull { it.stringValue }
                ?.let(onCode)
        }
    }

    private class PhotoDelegate(private val onImage: (UIImage?) -> Unit) : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
        override fun captureOutput(output: AVCapturePhotoOutput, didFinishProcessingPhoto: AVCapturePhoto, error: NSError?) {
            val data = if (error == null) didFinishProcessingPhoto.fileDataRepresentation() else null
            onImage(data?.let { UIImage(data = it) })
        }
    }

    companion object {
        /** `false` on the simulator: then no permission is asked at all. */
        val isPresent: Boolean
            get() = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) != null

        val isAuthorized: Boolean
            get() = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized

        /** Asks for camera access (the system dialog appears only the first time). */
        suspend fun requestAccess(): Boolean = suspendCancellableCoroutine { cont ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                dispatch_async(dispatch_get_main_queue()) { cont.resume(granted) }
            }
        }
    }
}
