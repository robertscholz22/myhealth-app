package com.myhealth.ui.common

import java.util.Locale

actual fun formatResourceString(pattern: String, args: Array<out Any>): String =
    if (args.isEmpty()) pattern else String.format(Locale.getDefault(), pattern, *args)
