package com.myhealth.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Files on iOS (P22.2). The shared code addresses documents by URI string; here every document
 * the app reads or writes is a `file://` URL inside the sandbox:
 * - opened documents (Files picker, "Open in MyHealth", share sheet) are copied into
 *   `Caches/Inbox` first, so an import running later still has them;
 * - a backup is written to `Caches/Export/<name>` and handed to the system "save to Files"
 *   picker as soon as the file is complete ([presentExport]).
 */
@OptIn(ExperimentalForeignApi::class)
object IosDocuments {

    private const val INBOX = "Inbox"
    private const val EXPORT = "Export"

    /** `Caches/<name>`, created on first use. */
    fun cacheDir(name: String): String {
        val caches = NSFileManager.defaultManager.URLForDirectory(NSCachesDirectory, NSUserDomainMask, null, true, null)
        val dir = requireNotNull(caches?.path) { "No Caches directory" } + "/" + name
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    /** Where a backup named [fileName] is written before the user picks its place. */
    fun exportUri(fileName: String): String = NSURL.fileURLWithPath(cacheDir(EXPORT) + "/" + fileName.safeName()).absoluteString!!

    fun isExport(path: String): Boolean = path.startsWith(cacheDir(EXPORT) + "/")

    /**
     * Copies [url] (security-scoped when it comes from outside the sandbox) into the inbox and
     * returns the copy's `file://` URI, or `null` when it cannot be read.
     */
    fun copyIntoSandbox(url: NSURL): String? {
        val name = (url.lastPathComponent ?: "document").safeName()
        val target = cacheDir(INBOX) + "/" + name
        val manager = NSFileManager.defaultManager
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            if (manager.fileExistsAtPath(target)) manager.removeItemAtPath(target, error = null)
            val ok = manager.copyItemAtURL(url, toURL = NSURL.fileURLWithPath(target), error = null)
            return if (ok) NSURL.fileURLWithPath(target).absoluteString else null
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    /** The view controller on top, to present the system pickers from. */
    fun topViewController(): UIViewController? {
        @Suppress("DEPRECATION")
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }

    /** Opens the Files picker for any document; [onResult] gets the sandbox copy or `null`. */
    fun presentOpener(onResult: (String?) -> Unit) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
        present(picker) { url -> onResult(url?.let { copyIntoSandbox(it) }) }
    }

    /** Offers the finished file at [path] to "save to Files"; the copy in the cache is then removed. */
    fun presentExport(path: String) {
        dispatch_async(dispatch_get_main_queue()) {
            val url = NSURL.fileURLWithPath(path)
            val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
            present(picker) { NSFileManager.defaultManager.removeItemAtURL(url, error = null) }
        }
    }

    /** Pickers hold their delegate weakly, so the one being shown is kept here. */
    private var activeDelegate: PickerDelegate? = null

    private fun present(picker: UIDocumentPickerViewController, onDone: (NSURL?) -> Unit) {
        val delegate = PickerDelegate { url ->
            activeDelegate = null
            onDone(url)
        }
        activeDelegate = delegate
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null) ?: run {
            activeDelegate = null
            onDone(null)
        }
    }

    private class PickerDelegate(private val onDone: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            onDone(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            onDone(null)
        }
    }

    private fun String.safeName(): String = replace('/', '_').ifBlank { "document" }
}
