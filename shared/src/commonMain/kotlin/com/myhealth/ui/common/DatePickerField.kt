package com.myhealth.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.myhealth.domain.util.utcDateOfMillis
import com.myhealth.domain.util.utcMidnightMillis
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.myhealth.ui.theme.MyHealthTheme
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Read-only date field (§4.3) that opens a Material 3 [DatePickerDialog] on tap. Dates are
 * exchanged as [LocalDate]; the dialog itself operates in UTC epoch millis, converted at the
 * boundary so the calendar day shown never shifts with the device zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    label: String,
    value: LocalDate?,
    onValueChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    var showDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value?.toString() ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            isError = isError,
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { showDialog = true },
        ) {}
    }

    if (showDialog) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value?.utcMidnightMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onValueChange(utcDateOfMillis(millis))
                    }
                    showDialog = false
                }) { Text(stringResource(Res.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(Res.string.action_cancel)) } },
        ) {
            DatePicker(state = state)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DatePickerFieldPreview() {
    MyHealthTheme(dynamicColor = false) {
        DatePickerField(label = "Birth date", value = LocalDate(1990, 5, 20), onValueChange = {})
    }
}
