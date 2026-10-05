package com.myhealth.data.time

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinTimeZone

actual typealias PlatformClock = java.time.Clock

actual val PlatformClock.timeZone: TimeZone
    get() = zone.toKotlinTimeZone()

actual fun systemClock(): PlatformClock = java.time.Clock.systemDefaultZone()
