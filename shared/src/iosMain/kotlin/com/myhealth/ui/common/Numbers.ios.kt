package com.myhealth.ui.common

import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterRoundHalfUp
import platform.Foundation.NSNumberFormatterRoundHalfEven

actual fun fmtDecimal(value: Double, digits: Int): String {
    val format = NSNumberFormatter()
    format.numberStyle = NSNumberFormatterDecimalStyle
    format.minimumFractionDigits = digits.toULong()
    format.maximumFractionDigits = digits.toULong()
    format.usesGroupingSeparator = false
    format.roundingMode = NSNumberFormatterRoundHalfUp
    return format.stringFromNumber(NSNumber(value)) ?: value.toString()
}

actual fun fmtInt(value: Number): String {
    val format = NSNumberFormatter()
    format.numberStyle = NSNumberFormatterDecimalStyle
    format.maximumFractionDigits = 0u
    format.usesGroupingSeparator = true
    // java.text's integer instance rounds half-even.
    format.roundingMode = NSNumberFormatterRoundHalfEven
    return format.stringFromNumber(NSNumber(value.toDouble())) ?: value.toString()
}
