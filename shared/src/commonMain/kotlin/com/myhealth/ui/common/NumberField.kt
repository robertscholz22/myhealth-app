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
import androidx.compose.ui.focus.onFocusChanged
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
    /**
     * Inclusive bounds. A typed number outside them is shown as an error and reported as `null`
     * ("no valid value"), never as the number — and never silently as the last valid one.
     */
    min: Double? = null,
    max: Double? = null,
    rangeError: String? = null,
) {
    var text by remember { mutableStateOf(value.toDisplayText(decimals)) }
    var clearedByUser by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val outOfRange = parseDecimal(text)?.let { (min != null && it < min) || (max != null && it > max) } ?: false
    // POLISH-20: the field owns its text while the user types, but an *external* change of
    // [value] (a prescription arriving after the sheet opened, a profile reloaded) must show up —
    // only when it differs from what the current text already parses to, so typing "2." is
    // never normalised away.
    // A field the user has just emptied is left empty even when the caller maps "empty" to a
    // value (onboarding stores 0 sessions): re-filling it put "0" back in front of the cursor,
    // so typing 3 made "30", clamped to 14 (found during P19 verification).
    // BUG-27: while the field has the focus its own text wins. A value that arrives then is the
    // echo of an earlier keystroke coming back from the store ("1" while the text is already
    // "19"), and writing it into the buffer dropped or reordered what was typed.
    LaunchedEffect(value, focused) {
        if (!focused && !clearedByUser && !outOfRange && parseDecimal(text) != value) {
            text = value.toDisplayText(decimals)
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { candidate ->
            if (candidate.isValidNumberInput(allowNegative)) {
                text = candidate
                clearedByUser = candidate.isEmpty()
                val parsed = parseDecimal(candidate)
                val inRange = parsed == null || ((min == null || parsed >= min) && (max == null || parsed <= max))
                onValueChange(if (inRange) parsed else null)
            }
        },
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        enabled = enabled,
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        isError = isError || outOfRange,
        supportingText = (if (outOfRange) rangeError ?: supportingText else supportingText)?.let { { Text(it) } },
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
