package com.myhealth.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.myhealth.di.IosAppGraph
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The iOS platform slot (P21): the Apple Health permission sheet (P22.1); the camera scanner
 * (AVFoundation + Vision, P22.3) is still a placeholder whose text is deliberately not a string
 * resource, as it disappears with P22.3.
 */
class IosPlatformUi(private val graph: IosAppGraph) : PlatformUi {

    @Composable
    override fun ScanScreen(onBack: () -> Unit, onReview: () -> Unit, onIngredient: (barcode: String) -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("The camera scanner is not available on iPhone yet.")
            TextButton(onClick = onBack) { Text("Back") }
        }
    }

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
