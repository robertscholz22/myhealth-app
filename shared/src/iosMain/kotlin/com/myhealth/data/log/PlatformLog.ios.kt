package com.myhealth.data.log

import platform.Foundation.NSLog

actual object PlatformLog {
    actual fun w(tag: String, message: String) {
        NSLog("%@ W: %@", tag, message)
    }
}
