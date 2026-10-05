package com.myhealth.data.log

import platform.Foundation.NSLog

actual object PlatformLog {
    actual fun w(tag: String, message: String) {
        // Kotlin/Native hands a String to a C variadic as a UTF-8 C string, so it needs `%s`;
        // `%@` would read the bytes as an object pointer and crash.
        NSLog("%s W: %s", tag, message)
    }
}
