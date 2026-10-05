package com.myhealth.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.ui.theme.MyHealthTheme

/**
 * Shown by Health Connect when the user taps "Read the privacy policy" on the permission sheet,
 * and by Android 14+ system settings through the `ViewPermissionUsageActivity` alias
 * (PLAN P2.1). Health Connect will not grant permissions to an app that does not declare it.
 *
 * Deliberately standalone: it is launched by the system, not from the nav graph, so it holds no
 * `AppGraph` and reads nothing. Plain static text only.
 */
class PermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyHealthTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionsRationaleContent()
                }
            }
        }
    }
}

@Composable
private fun PermissionsRationaleContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.hc_rationale_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(Res.string.hc_rationale_what_we_read),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(Res.string.hc_rationale_why),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(Res.string.hc_rationale_on_device),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(Res.string.hc_rationale_revoke),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionsRationaleContentPreview() {
    MyHealthTheme { PermissionsRationaleContent() }
}
