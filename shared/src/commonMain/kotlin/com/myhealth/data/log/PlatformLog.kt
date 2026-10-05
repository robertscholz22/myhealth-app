package com.myhealth.data.log

/** Warning log for shared data code: Logcat on Android, the unified log on iOS. */
expect object PlatformLog {
    fun w(tag: String, message: String)
}
