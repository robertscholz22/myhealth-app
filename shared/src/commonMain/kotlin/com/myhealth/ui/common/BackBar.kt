package com.myhealth.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.myhealth.resources.Res
import com.myhealth.resources.action_back
import org.jetbrains.compose.resources.StringResource

/**
 * A title bar with a back arrow for the screens opened from More that have none of their own
 * (P22). Android users go back with the system back gesture, so there [content] is shown as is;
 * an iPhone has no back key, so it gets the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: StringResource, onBack: () -> Unit, content: @Composable () -> Unit) {
    if (platformHasBackKey) {
        content()
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) { content() }
    }
}
