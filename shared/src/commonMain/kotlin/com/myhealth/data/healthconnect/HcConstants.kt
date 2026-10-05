package com.myhealth.data.healthconnect

/**
 * The Health Connect integer constants the shared mappers read (connect-client 1.1.0). The HC
 * DTOs carry them as raw `Int`s, and since P20.2 the mappers live in common code where the
 * `androidx.health.connect` classes do not exist — so the values are spelled out here, and
 * `HcConstantsTest` pins every one to the library constant of the same name (risk R3: a rename
 * or renumbering fails a test instead of silently mis-mapping). The iOS HealthKit reader (P22)
 * translates into the same vocabulary.
 */
object HcExerciseType {
    const val OTHER_WORKOUT: Int = 0
    const val BIKING: Int = 8
    const val BIKING_STATIONARY: Int = 9
    const val HIGH_INTENSITY_INTERVAL_TRAINING: Int = 36
    const val HIKING: Int = 37
    const val PILATES: Int = 48
    const val ROWING: Int = 53
    const val ROWING_MACHINE: Int = 54
    const val RUNNING: Int = 56
    const val RUNNING_TREADMILL: Int = 57
    const val SOCCER: Int = 64
    const val STRENGTH_TRAINING: Int = 70
    const val STRETCHING: Int = 71
    const val SWIMMING_OPEN_WATER: Int = 73
    const val SWIMMING_POOL: Int = 74
    const val WALKING: Int = 79
    const val WEIGHTLIFTING: Int = 81
    const val YOGA: Int = 83
}

/** `SleepSessionRecord.STAGE_TYPE_*` (connect-client 1.1.0); see [HcExerciseType]. */
object HcSleepStageType {
    const val UNKNOWN: Int = 0
    const val AWAKE: Int = 1
    const val SLEEPING: Int = 2
    const val OUT_OF_BED: Int = 3
    const val LIGHT: Int = 4
    const val DEEP: Int = 5
    const val REM: Int = 6
    const val AWAKE_IN_BED: Int = 7
}
