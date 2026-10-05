package com.myhealth.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.ui.theme.MyHealthTheme

/**
 * The one loading affordance the app uses while a screen's first data is still in flight (P8.6,
 * §4.3). Deliberately a centred spinner rather than a skeleton: every screen here fills from a
 * local Room flow, so the wait is a frame or two and a shimmering placeholder would flash.
 */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadingBoxPreview() {
    MyHealthTheme(dynamicColor = false) { LoadingBox() }
}
