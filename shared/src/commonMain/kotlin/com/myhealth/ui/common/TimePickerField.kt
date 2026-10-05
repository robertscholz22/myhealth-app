package com.myhealth.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.myhealth.ui.theme.MyHealthTheme

/** Default time offered when the field has no value yet: 09:00. */
private const val DEFAULT_HOUR = 9
private const val MINUTES_PER_HOUR = 60

/**
 * Read-only time field (§4.2 Event edit, P3.6) that opens a Material 3 [TimePicker] in a plain
 * [Dialog] on tap — M3 ships `DatePickerDialog` but no `TimePickerDialog` counterpart. The value
 * is exchanged as minutes-since-midnight (`startMinuteOfDay`, §2.2.4), matching the entity column
 * so the screen never has to convert at the boundary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerField(
    label: String,
    value: Int?,
    onValueChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    var showDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value?.let(::formatMinuteOfDayLocal).orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            isError = isError,
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
        )
        Box(modifier = Modifier.matchParentSize().clickable { showDialog = true }) {}
    }

    if (showDialog) {
        val initialHour = value?.let { it / MINUTES_PER_HOUR } ?: DEFAULT_HOUR
        val initialMinute = value?.let { it % MINUTES_PER_HOUR } ?: 0
        val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
        Dialog(onDismissRequest = { showDialog = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge) {
                Column(modifier = Modifier.padding(24.dp)) {
                    TimePicker(state = state)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        TextButton(onClick = { showDialog = false }) { Text(stringResource(Res.string.action_cancel)) }
                        TextButton(onClick = {
                            onValueChange(state.hour * MINUTES_PER_HOUR + state.minute)
                            showDialog = false
                        }) { Text(stringResource(Res.string.common_ok)) }
                    }
                }
            }
        }
    }
}

private fun formatMinuteOfDayLocal(minuteOfDay: Int): String {
    val clamped = minuteOfDay.coerceIn(0, 24 * MINUTES_PER_HOUR - 1)
    return "${(clamped / MINUTES_PER_HOUR).zeroPad(2)}:${(clamped % MINUTES_PER_HOUR).zeroPad(2)}"
}

@Preview(showBackground = true)
@Composable
private fun TimePickerFieldPreview() {
    MyHealthTheme(dynamicColor = false) {
        TimePickerField(label = "Start time", value = 19 * 60 + 30, onValueChange = {})
    }
}
