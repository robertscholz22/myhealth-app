package com.myhealth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.LocalAppGraph
import com.myhealth.domain.model.AppSettings
import com.myhealth.ui.common.LocalPlatformUi
import com.myhealth.ui.nav.MyHealthNavHost
import com.myhealth.ui.theme.MyHealthTheme
import com.myhealth.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as MyHealthApp).graph
        handleImportIntent(intent)
        setContent {
            // The theme is settings-driven (P8.6a): `themeMode` picks light/dark and
            // `useDynamicColor` (default false) decides green scheme vs wallpaper colours.
            val settings by graph.settings.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            val platform = remember { AndroidPlatformUi(this, graph.hcIntegration) }
            CompositionLocalProvider(LocalAppGraph provides graph, LocalPlatformUi provides platform) {
                MyHealthTheme(
                    darkTheme = isDarkTheme(settings.themeMode),
                    dynamicColor = settings.useDynamicColor,
                ) {
                    MyHealthNavHost()
                }
            }
        }
    }

    /**
     * A second share while the app is already running (P7.6). The activity is `singleTask`, so
     * the new intent arrives here rather than in a fresh instance.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    /**
     * Parks a shared/viewed `.fit`, `.csv` or `.zip` in the graph; `MyHealthNavHost` navigates to
     * the Import screen when it sees one, and the screen starts the import and clears it (P7.6).
     */
    private fun handleImportIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let { (application as MyHealthApp).graph.pendingImportUri.value = it.toString() }
    }
}
