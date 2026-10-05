package com.myhealth.ui.common

import kotlin.math.roundToLong

/**
 * The one number-formatting convention for the UI (POLISH-12). Before this file, some screens
 * formatted decimals with `"%.1f".format(...)` / `String.format(...)` using the *default* locale
 * (which reads "," as the decimal separator on a German phone — "8,8" instead of "8.8" — and
 * breaks parsing when the same string is fed back through `toDoubleOrNull()`), while others used
 * `String.format(Locale.US, ...)` or hand-written dot literals, which never localize at all. Every
 * number shown to the user, or read back from a user-entered string, should go through one of the
 * functions below instead. `domain/` formats nothing and is untouched by this change.
 *
 * [fmtDecimal] and the unit helpers built on it never group by thousands — that matches the
 * `"%.Nf"`-style output they replace. [fmtInt] does group, since it replaces whole-number display
 * (session counts, calorie/macro targets) that read naturally with thousands separators.
 *
 * P20.3: both depend on the device locale and are platform functions (`java.text` on Android, as
 * before; `NSNumberFormatter` on iOS).
 */

/**
 * [value] with exactly [digits] fraction digits, using the device locale's decimal separator and
 * half-up rounding, no grouping. E.g. `fmtDecimal(8.8, 1)` is `"8.8"` under `Locale.US` and `"8,8"`
 * under `Locale.GERMANY`.
 */
expect fun fmtDecimal(value: Double, digits: Int): String

/**
 * [value] as a whole number with the device locale's grouping separator, e.g. `fmtInt(1234)` is
 * `"1,234"` under `Locale.US` and `"1.234"` under `Locale.GERMANY`.
 */
expect fun fmtInt(value: Number): String

/**
 * `Math.round(value)`: half-up to a `Long`, `NaN` → 0 (Kotlin's `roundToLong` would throw), the
 * infinities clamp to `Long.MIN_VALUE`/`MAX_VALUE`.
 */
fun mathRound(value: Double): Long = if (value.isNaN()) 0L else value.roundToLong()

/** [value] in kilograms, e.g. `"77.0 kg"`. */
fun fmtKg(value: Double, digits: Int = 1): String = "${fmtDecimal(value, digits)} kg"

/** [value] in kilometres, e.g. `"7.20 km"`. */
fun fmtKm(value: Double, digits: Int = 2): String = "${fmtDecimal(value, digits)} km"

/** [value] as a percentage (the caller passes the already-scaled 0-100 number), e.g. `"58.7 %"`. */
fun fmtPercent(value: Double, digits: Int = 1): String = "${fmtDecimal(value, digits)} %"

/**
 * Parses user-entered numeric text, accepting either "," or "." as the decimal separator — every
 * numeric input (`NumberField` and everything built on it: quantity editors, goal/plan/ingredient/
 * body/onboarding drafts) routes through this so a German keyboard's "," is accepted, not just a
 * dot. Deliberately not locale-restricted to a single separator: a text field never contains a
 * grouping separator, so accepting both marks is simpler and more forgiving than switching on
 * the device locale. Blank or unparsable text (including a lone "-" or ".") returns `null`.
 */
fun parseDecimal(text: String): Double? {
    val normalized = text.trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    return normalized.toDoubleOrNull()
}
