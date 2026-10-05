package com.myhealth.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.myhealth.platform.IosDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import platform.Foundation.NSURL
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** The Files picker; the picked document arrives as a `file://` copy inside the sandbox (P22.2). */
@Composable
actual fun rememberDocumentOpener(persistReadAccess: Boolean, onResult: (uri: String?) -> Unit): (mimeTypes: List<String>) -> Unit {
    val latest by rememberUpdatedState(onResult)
    // iOS has no content type for `.fit`, so every file is offered; the import checks the name.
    return remember { { _ -> IosDocuments.presentOpener { latest(it) } } }
}

/**
 * "Save as" in two steps (P22.2): the caller writes to a file in the cache, and the moment that file
 * is closed the system "save to Files" picker offers it (see `IosDocuments.presentExport`).
 */
@Composable
actual fun rememberDocumentCreator(mimeType: String, onResult: (uri: String?) -> Unit): (suggestedName: String) -> Unit {
    val latest by rememberUpdatedState(onResult)
    return remember { { name -> latest(IosDocuments.exportUri(name)) } }
}

/** Every document is a sandbox file, so its name is the URL's last path component. */
@Composable
actual fun rememberDocumentInfo(): (uri: String) -> DocumentInfo = remember {
    { uri ->
        val name = NSURL.URLWithString(uri)?.lastPathComponent ?: uri.substringAfterLast('/')
        DocumentInfo(name, null)
    }
}

/** Settings › Accessibility › Motion › Reduce Motion. */
@Composable
actual fun isReducedMotionEnabled(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }

/** iOS has no wallpaper colours. */
@Composable
actual fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

actual suspend fun loadHalfSizeImage(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val image = Image.makeFromEncoded(FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
        val width = (image.width / 2).coerceAtLeast(1)
        val height = (image.height / 2).coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
        surface.makeImageSnapshot().toComposeImageBitmap()
    }.getOrNull()
}

actual val platformHealth: com.myhealth.di.HealthPlatform = com.myhealth.di.HealthPlatform.APPLE_HEALTH

actual val platformHasBackKey: Boolean = false
