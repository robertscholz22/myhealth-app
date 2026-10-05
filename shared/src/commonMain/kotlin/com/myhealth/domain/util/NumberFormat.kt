package com.myhealth.domain.util

import kotlin.math.abs

/**
 * Locale-free number formatting for the shared code (P20.1), replacing `String.format(Locale.US, …)`.
 *
 * [fixed] reproduces Java's `%.nf`: the double's shortest decimal representation is rounded
 * **half-up** at [decimals] places (so `0.15` → `"0.2"`, unlike C's `printf`). `NumberFormatTest`
 * checks it against `String.format` on the JVM.
 */
object NumberFormat {

    /** `String.format(Locale.US, "%.${decimals}f", value)`. */
    fun fixed(value: Double, decimals: Int): String {
        require(decimals >= 0) { "decimals must be >= 0" }
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
        val (digits, pointPos) = decimalDigits(abs(value))
        // digits: significant digits without leading zeros; value = 0.digits × 10^pointPos.
        val keep = pointPos + decimals // number of digits that survive rounding
        val rounded: String
        val newPoint: Int
        if (keep < 0) {
            rounded = ""
            newPoint = pointPos
        } else if (keep >= digits.length) {
            rounded = digits
            newPoint = pointPos
        } else {
            val head = digits.substring(0, keep)
            if (digits[keep] >= '5') {
                val inc = incrementDecimal(head.ifEmpty { "0" })
                if (head.isEmpty()) {
                    rounded = inc
                    newPoint = pointPos + 1
                } else if (inc.length > head.length) {
                    rounded = inc
                    newPoint = pointPos + 1
                } else {
                    rounded = inc
                    newPoint = pointPos
                }
            } else {
                rounded = head
                newPoint = pointPos
            }
        }
        val intPart: String
        val fracSource: String
        if (newPoint <= 0) {
            intPart = "0"
            fracSource = "0".repeat(-newPoint) + rounded
        } else if (rounded.length <= newPoint) {
            intPart = (rounded + "0".repeat(newPoint - rounded.length)).ifEmpty { "0" }
            fracSource = ""
        } else {
            intPart = rounded.substring(0, newPoint)
            fracSource = rounded.substring(newPoint)
        }
        val frac = fracSource.take(decimals).padEnd(decimals, '0')
        val body = if (decimals == 0) intPart else "$intPart.$frac"
        return if (negative) "-$body" else body
    }

    /** `"%d"`-style with an explicit sign: `"+12"`, `"-3"`, `"+0"`. */
    fun signed(value: Int): String = if (value >= 0) "+$value" else value.toString()

    /** `"%+.${decimals}f"`. */
    fun signedFixed(value: Double, decimals: Int): String {
        val text = fixed(value, decimals)
        return if (text.startsWith("-")) text else "+$text"
    }

    /** Lower-case hex of every byte (`"%02x"` per byte). */
    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    /**
     * Significant digits of the shortest representation of [value] (≥ 0) and the position of the
     * decimal point relative to them; `0.0` gives `("0", 1)`.
     */
    private fun decimalDigits(value: Double): Pair<String, Int> {
        val text = value.toString().lowercase()
        val mantissa = text.substringBefore('e')
        val exponent = if ('e' in text) text.substringAfter('e').toInt() else 0
        val intPart = mantissa.substringBefore('.')
        val fracPart = if ('.' in mantissa) mantissa.substringAfter('.') else ""
        val all = intPart + fracPart
        var point = intPart.length + exponent
        val firstNonZero = all.indexOfFirst { it != '0' }
        if (firstNonZero < 0) return "0" to 1
        point -= firstNonZero
        val digits = all.substring(firstNonZero).trimEnd('0').ifEmpty { "0" }
        return digits to point
    }

    private fun incrementDecimal(digits: String): String {
        val chars = digits.toCharArray()
        var i = chars.size - 1
        while (i >= 0) {
            if (chars[i] == '9') {
                chars[i] = '0'
                i--
            } else {
                chars[i] = chars[i] + 1
                return chars.concatToString()
            }
        }
        return "1" + chars.concatToString()
    }

    private const val HEX = "0123456789abcdef"
}

/** Two-digit zero padding (`"%02d"`) for non-negative values. */
fun Int.pad2(): String = toString().padStart(2, '0')

/** `m:ss`, or `h:mm:ss` from one hour (`"%d:%02d"` / `"%d:%02d:%02d"`). */
fun clockLabel(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "$h:${m.pad2()}:${s.pad2()}" else "$m:${s.pad2()}"
}
