package com.myhealth.domain.engine.suggest

import kotlin.time.Clock
import kotlin.time.Instant

/** The Android app (and its tests) hand engines a `java.time.Clock`; the shared code reads [Clock]. */
fun java.time.Clock.asKotlinClock(): Clock = object : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(this@asKotlinClock.millis())
}

/** `SuggestionEngine(clock)` with the app's `java.time.Clock`. */
fun SuggestionEngine(clock: java.time.Clock): SuggestionEngine = SuggestionEngine(clock.asKotlinClock())
