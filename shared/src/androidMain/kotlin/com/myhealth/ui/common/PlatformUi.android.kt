package com.myhealth.ui.common

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberDocumentOpener(persistReadAccess: Boolean, onResult: (uri: String?) -> Unit): (mimeTypes: List<String>) -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null && persistReadAccess) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        latest(uri?.toString())
    }
    return remember(launcher) { { mimeTypes -> launcher.launch(mimeTypes.toTypedArray()) } }
}

@Composable
actual fun rememberDocumentCreator(mimeType: String, onResult: (uri: String?) -> Unit): (suggestedName: String) -> Unit {
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(CreateDocument(mimeType)) { uri -> latest(uri?.toString()) }
    return remember(launcher) { { name -> launcher.launch(name) } }
}

@Composable
actual fun rememberDocumentInfo(): (uri: String) -> DocumentInfo {
    val context = LocalContext.current
    return remember(context) {
        { raw ->
            val uri = Uri.parse(raw)
            DocumentInfo(displayNameOf(context, uri), context.contentResolver.getType(uri))
        }
    }
}

/** `OpenableColumns.DISPLAY_NAME` when the provider offers it, else the last path segment. */
private fun displayNameOf(context: android.content.Context, uri: Uri): String {
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0) }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
}

/** The "Remove animations" developer option. Read once per composition — the setting does not
 * change while a figure is on screen, and there is no change listener to key a `remember` off. */
@Composable
actual fun isReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
actual fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? {
    val context = LocalContext.current
    return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

actual suspend fun loadHalfSizeImage(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
    }.getOrNull()?.asImageBitmap()
}

actual val platformHealth: com.myhealth.di.HealthPlatform = com.myhealth.di.HealthPlatform.HEALTH_CONNECT
