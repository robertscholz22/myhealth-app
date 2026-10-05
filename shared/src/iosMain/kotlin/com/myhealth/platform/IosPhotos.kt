package com.myhealth.platform

import platform.Foundation.NSData
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIImage
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * "From photo" on iOS (P22.3): the system photo picker, which runs out of process and needs no
 * photo-library permission — the iOS twin of Android's `PickVisualMedia`.
 */
object IosPhotos {

    /** The picker holds its delegate weakly, so the one being shown is kept here. */
    private var activeDelegate: Delegate? = null

    /** Shows the picker; [onResult] gets the chosen picture, or `null` when the user backed out. */
    fun pick(onResult: (UIImage?) -> Unit) {
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter()
            selectionLimit = 1
        }
        val picker = PHPickerViewController(configuration = config)
        val delegate = Delegate { image ->
            activeDelegate = null
            onResult(image)
        }
        activeDelegate = delegate
        picker.delegate = delegate
        IosDocuments.topViewController()?.presentViewController(picker, animated = true, completion = null)
            ?: delegate.finish(null)
    }

    private class Delegate(private val onImage: (UIImage?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
        private var done = false

        fun finish(image: UIImage?) {
            if (done) return
            done = true
            onImage(image)
        }

        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            picker.dismissViewControllerAnimated(true, completion = null)
            val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider ?: return finish(null)
            provider.loadDataRepresentationForTypeIdentifier(UTTypeImage.identifier) { data: NSData?, _ ->
                val image = data?.let { UIImage(data = it) }
                dispatch_async(dispatch_get_main_queue()) { finish(image) }
            }
        }
    }
}
