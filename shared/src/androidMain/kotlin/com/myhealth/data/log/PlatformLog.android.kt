package com.myhealth.data.log

import android.util.Log

actual object PlatformLog {
    actual fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
}
