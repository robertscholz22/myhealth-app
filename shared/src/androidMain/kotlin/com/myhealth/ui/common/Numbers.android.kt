package com.myhealth.ui.common

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale

actual fun fmtDecimal(value: Double, digits: Int): String {
    val format = NumberFormat.getNumberInstance(Locale.getDefault()) as DecimalFormat
    format.minimumFractionDigits = digits
    format.maximumFractionDigits = digits
    format.isGroupingUsed = false
    format.roundingMode = RoundingMode.HALF_UP
    return format.format(value)
}

actual fun fmtInt(value: Number): String {
    val format = NumberFormat.getIntegerInstance(Locale.getDefault())
    format.isGroupingUsed = true
    return format.format(value.toDouble())
}
