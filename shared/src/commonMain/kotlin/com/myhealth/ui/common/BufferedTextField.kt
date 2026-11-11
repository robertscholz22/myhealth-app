package com.myhealth.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * A single-line text field for a value that lives in an asynchronous store (BUG-27).
 *
 * The field keeps its own text and reports every change; the stored [value] is only copied back
 * while the field does **not** have the focus. Feeding a store-backed flow straight into
 * `OutlinedTextField.value` hands the field its own keystrokes back a frame late, which reorders
 * and drops characters when typing fast.
 */
@Composable
fun BufferedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused && text != value) text = value
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
    )
}
