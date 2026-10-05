package com.myhealth.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The screens and actions that only the platform shell can provide (P20.3), because they need its
 * own integrations: the live camera scanner (CameraX + ML Kit on Android) and the health-data
 * permission flow. Provided once by the entry point (`MainActivity`) through [LocalPlatformUi].
 */
interface PlatformUi {

    /** The camera scanner (PLAN P4.8): a label goes to OCR review, a barcode to the ingredient editor. */
    @Composable
    fun ScanScreen(onBack: () -> Unit, onReview: () -> Unit, onIngredient: (barcode: String) -> Unit)

    /** Remembers the health-permission request; calling the result asks for the given permissions. */
    @Composable
    fun rememberHealthPermissionRequest(onResult: (granted: Set<String>) -> Unit): (permissions: Set<String>) -> Unit

    /** Opens the store page of the health-data app (install or update). */
    fun openHealthAppStore()

    /** Opens the health-data app's own permission settings. */
    fun openHealthSettings()
}

val LocalPlatformUi = staticCompositionLocalOf<PlatformUi> { error("PlatformUi missing") }

/** The health store this platform syncs with (P22): names it in the UI. */
expect val platformHealth: com.myhealth.di.HealthPlatform

/** `true` where the system offers a back key or gesture for every screen (Android). */
expect val platformHasBackKey: Boolean

/**
 * Remembers a document picker (PLAN P7.6, P8.4). Calling the result opens it for [mimeTypes];
 * [onResult] receives the picked document's platform reference (a `content://` URI on Android) or
 * `null` when the user backed out. [persistReadAccess] keeps read access across restarts.
 */
@Composable
expect fun rememberDocumentOpener(persistReadAccess: Boolean, onResult: (uri: String?) -> Unit): (mimeTypes: List<String>) -> Unit

/** Remembers a "save as" picker for [mimeType]; calling the result proposes a file name. */
@Composable
expect fun rememberDocumentCreator(mimeType: String, onResult: (uri: String?) -> Unit): (suggestedName: String) -> Unit

/** A picked document as the Import screen shows it. */
data class DocumentInfo(val displayName: String, val mimeType: String?)

/** Remembers a lookup of a document's display name and MIME type. */
@Composable
expect fun rememberDocumentInfo(): (uri: String) -> DocumentInfo

/** `true` when the device asks for animations to be removed (the body-figure clips then stand still). */
@Composable
expect fun isReducedMotionEnabled(): Boolean

/** The wallpaper-based scheme where the platform has one (Android 12+), otherwise `null`. */
@Composable
expect fun dynamicColorScheme(darkTheme: Boolean): ColorScheme?

/** The picture at [path], decoded at half resolution, or `null` when it cannot be read. */
expect suspend fun loadHalfSizeImage(path: String): ImageBitmap?
