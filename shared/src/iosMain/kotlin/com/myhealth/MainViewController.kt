package com.myhealth

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.ComposeUIViewController
import com.myhealth.di.IosAppGraph
import com.myhealth.di.LocalAppGraph
import com.myhealth.domain.model.AppSettings
import com.myhealth.ui.common.IosPlatformUi
import com.myhealth.ui.common.LocalPlatformUi
import com.myhealth.ui.nav.MyHealthNavHost
import com.myhealth.ui.theme.MyHealthTheme
import com.myhealth.ui.theme.isDarkTheme
import platform.UIKit.UIViewController

/**
 * The iOS entry point (P21), hosted by the Swift app (`iosApp/MyHealth/ContentView.swift`): the same
 * composition as Android's `MainActivity.setContent` — graph, platform slot, settings-driven theme,
 * navigation host.
 *
 * Two iOS-only additions, because an iPhone has no back key to hide the keyboard and its number pad
 * no return key: the content is laid out above the keyboard (like Android's `adjustResize`), and a
 * tap outside a text field clears the focus, which closes the keyboard.
 */
@Suppress("FunctionName", "unused") // Called from Swift.
fun MainViewController(): UIViewController {
    val graph = IosAppGraph.instance
    return ComposeUIViewController {
        val settings by graph.settings.settings.collectAsState(initial = AppSettings())
        val platform = remember { IosPlatformUi(graph) }
        CompositionLocalProvider(LocalAppGraph provides graph, LocalPlatformUi provides platform) {
            MyHealthTheme(
                darkTheme = isDarkTheme(settings.themeMode),
                dynamicColor = settings.useDynamicColor,
            ) {
                val focusManager = LocalFocusManager.current
                Box(
                    Modifier
                        .fillMaxSize()
                        .imePadding()
                        .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
                ) {
                    MyHealthNavHost()
                }
            }
        }
    }
}

/**
 * Called by the Swift app whenever it becomes active (P22.1): every return to the app pulls the
 * latest data, whatever background runs iOS granted meanwhile (P22.4).
 */
@Suppress("unused") // Called from Swift.
fun onAppBecameActive() {
    IosAppGraph.instance.syncScheduler.syncNow()
    com.myhealth.platform.IosBackground.startHealthObservers()
}

/** Called by the Swift app from `init`: background refresh and HealthKit observers (P22.4). */
@Suppress("unused") // Called from Swift.
fun onAppLaunched() {
    com.myhealth.platform.IosBackground.register()
}

/** Called by the Swift app when it goes to the background: asks iOS for the next refresh. */
@Suppress("unused") // Called from Swift.
fun onAppEnteredBackground() {
    com.myhealth.platform.IosBackground.scheduleRefresh()
}

/**
 * A document opened in MyHealth from Files, Mail or the share sheet (P22.2): copied into the
 * sandbox and handed to the Import screen, like Android's `ACTION_VIEW`/`ACTION_SEND`.
 */
@Suppress("unused") // Called from Swift.
fun onOpenDocument(url: String) {
    val nsUrl = platform.Foundation.NSURL.URLWithString(url) ?: return
    val copy = com.myhealth.platform.IosDocuments.copyIntoSandbox(nsUrl) ?: return
    IosAppGraph.instance.pendingImportUri.value = copy
}
