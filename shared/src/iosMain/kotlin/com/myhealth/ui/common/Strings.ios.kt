package com.myhealth.ui.common

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.decimalSeparator

actual fun formatResourceString(pattern: String, args: Array<out Any>): String =
    if (args.isEmpty()) pattern
    else ResourceFormat.format(pattern, args, NSLocale.currentLocale.decimalSeparator.firstOrNull() ?: '.')
