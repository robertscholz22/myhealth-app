package com.myhealth.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.myhealth.ui.theme.MyHealthTheme

/**
 * Numeric text field (§4.3): `NumberField(label, value, onValueChange, suffix, decimals)`.
 *
 * Keeps its own text buffer so the user can type freely (leading "-", a trailing "."), only
 * calling [onValueChange] once the buffer parses to a [Double]; an unparsable buffer (including
 * blank) reports `null` upstream without discarding what the user typed.
 */
@Composable
fun NumberField(
    label: String,
    value: Double?,
    onValueChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimals: Int = 1,
    allowNegative: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    var text by remember { mutableStateOf(value.toDisplayText(decimals)) }
    var clearedByUser by remember { mutableStateOf(false) }
    // POLISH-20: the field owns its text while the user types, but an *external* change of
    // [value] (a prescription arriving after the sheet opened, a profile reloaded) must show up —
    // only when it differs from what the current text already parses to, so typing "2." is
    // never normalised away.
    // A field the user has just emptied is left empty even when the caller maps "empty" to a
    // value (onboarding stores 0 sessions): re-filling it put "0" back in front of the cursor,
    // so typing 3 made "30", clamped to 14 (found during P19 verification).
    LaunchedEffect(value) {
        if (!clearedByUser && parseDecimal(text) != value) text = value.toDisplayText(decimals)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { candidate ->
            if (candidate.isValidNumberInput(allowNegative)) {
                text = candidate
                clearedByUser = candidate.isEmpty()
                onValueChange(parseDecimal(candidate))
            }
        },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun Double?.toDisplayText(decimals: Int): String =
    this?.let { fmtDecimal(it, decimals) } ?: ""

/** Accepts either decimal separator as the user types — [parseDecimal] then normalizes whichever
 * one they used (POLISH-12: a German keyboard's numeric row inserts "," not "."). */
private fun String.isValidNumberInput(allowNegative: Boolean): Boolean {
    val pattern = if (allowNegative) "-?\\d*[.,]?\\d*" else "\\d*[.,]?\\d*"
    return this.matches(Regex(pattern))
}

@Preview(showBackground = true)
@Composable
private fun NumberFieldPreview() {
    MyHealthTheme(dynamicColor = false) {
        NumberField(label = "Height", value = 178.0, onValueChange = {}, suffix = "cm", decimals = 0)
    }
}
