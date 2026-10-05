package com.myhealth.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.myhealth.di.IosAppGraph
import com.myhealth.platform.IosBackground
import com.myhealth.ui.camera.IosScanScreen
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * The iOS platform slot: the camera scanner (AVFoundation + Vision, P22.3) and the Apple Health
 * permission sheet (P22.1).
 */
class IosPlatformUi(private val graph: IosAppGraph) : PlatformUi {

    @Composable
    override fun ScanScreen(onBack: () -> Unit, onReview: () -> Unit, onIngredient: (barcode: String) -> Unit) =
        IosScanScreen(onBack, onReview, onIngredient)

    /**
     * Shows HealthKit's own sheet (it only lists types not answered yet). The result is "all"
     * once answered — HealthKit hides the individual read choices — and a sync starts right away.
     */
    @Composable
    override fun rememberHealthPermissionRequest(onResult: (granted: Set<String>) -> Unit): (permissions: Set<String>) -> Unit {
        val scope = rememberCoroutineScope()
        return {
            scope.launch {
                graph.healthKit.request()
                onResult(graph.hcIntegration.granted())
                graph.syncScheduler.syncNow()
                IosBackground.startHealthObservers()
            }
        }
    }

    /** iOS has no store page for Apple Health: it is part of the system. */
    override fun openHealthAppStore() = Unit

    /** The app's page in Settings — Health access itself lives under Settings › Health. */
    override fun openHealthSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
