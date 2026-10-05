package com.myhealth.domain.engine.nutrition

import kotlinx.datetime.toKotlinTimeZone

/** §1.3 constructs engines with the app's `java.time.Clock`; only its zone is used. */
fun NutritionTargetEngine(clock: java.time.Clock): NutritionTargetEngine =
    NutritionTargetEngine(clock.zone.toKotlinTimeZone())
