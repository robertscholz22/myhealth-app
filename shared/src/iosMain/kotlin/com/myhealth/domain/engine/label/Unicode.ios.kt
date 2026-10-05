package com.myhealth.domain.engine.label

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun String.normalizeNfkd(): String =
    (this as NSString).decomposedStringWithCompatibilityMapping
