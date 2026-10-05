package com.myhealth.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

// P22 replaces these with UIDocumentPicker, UIAccessibility and image decoding; until then the
// iOS shell only builds.

@Composable
actual fun rememberDocumentOpener(persistReadAccess: Boolean, onResult: (uri: String?) -> Unit): (mimeTypes: List<String>) -> Unit =
    { onResult(null) }

@Composable
actual fun rememberDocumentCreator(mimeType: String, onResult: (uri: String?) -> Unit): (suggestedName: String) -> Unit =
    { onResult(null) }

@Composable
actual fun rememberDocumentInfo(): (uri: String) -> DocumentInfo =
    { uri -> DocumentInfo(uri.substringAfterLast('/'), null) }

@Composable
actual fun isReducedMotionEnabled(): Boolean = false

@Composable
actual fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

actual suspend fun loadHalfSizeImage(path: String): ImageBitmap? = null
