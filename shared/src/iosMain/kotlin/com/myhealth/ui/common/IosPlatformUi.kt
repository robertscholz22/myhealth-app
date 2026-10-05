package com.myhealth.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The iOS platform slot (P21). The camera scanner (AVFoundation + Vision) and the HealthKit
 * permission sheet arrive in P22; until then the scanner is a placeholder and the health calls do
 * nothing. The placeholder text is deliberately not a string resource: it disappears in P22.
 */
class IosPlatformUi : PlatformUi {

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

    @Composable
    override fun rememberHealthPermissionRequest(onResult: (granted: Set<String>) -> Unit): (permissions: Set<String>) -> Unit =
        { onResult(emptySet()) }

    override fun openHealthAppStore() = Unit

    override fun openHealthSettings() = Unit
}
