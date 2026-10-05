package com.myhealth.data.healthconnect

import com.myhealth.domain.model.SportType

/**
 * `ExerciseSessionRecord.exerciseType` → [SportType] (PLAN P2.3).
 *
 * Only the [HcExerciseType] **names** are referenced here; their values are pinned to the
 * connect-client's `EXERCISE_TYPE_*` constants by `HcConstantsTest` (risk R3), so a rename or
 * removal in a future connect-client fails a test rather than silently mis-mapping. Anything not in the table becomes [SportType.OTHER] — Health Connect
 * exercise types are coarse and a wrong guess would poison the load engine.
 *
 * Health Connect 1.1.0 has no soccer-vs-match distinction, so a soccer session is
 * [SportType.SOCCER_TRAINING] unless its title says otherwise; event linking (P3.3) can still
 * upgrade it later.
 */
object ExerciseTypeMap {

    /** Title words that turn a soccer session into a match — English and German. */
    private val MATCH_WORDS = listOf("match", "spiel")

    fun toSportType(exerciseType: Int, title: String? = null): SportType = when (exerciseType) {
        HcExerciseType.SOCCER ->
            if (isMatchTitle(title)) SportType.SOCCER_MATCH else SportType.SOCCER_TRAINING

        HcExerciseType.RUNNING -> SportType.RUN_OUTDOOR
        HcExerciseType.RUNNING_TREADMILL -> SportType.RUN_TREADMILL

        HcExerciseType.STRENGTH_TRAINING,
        HcExerciseType.WEIGHTLIFTING,
        -> SportType.STRENGTH

        HcExerciseType.BIKING -> SportType.CYCLING
        HcExerciseType.BIKING_STATIONARY -> SportType.CYCLING_INDOOR

        HcExerciseType.WALKING -> SportType.WALK
        HcExerciseType.HIKING -> SportType.HIKE

        HcExerciseType.SWIMMING_POOL,
        HcExerciseType.SWIMMING_OPEN_WATER,
        -> SportType.SWIM

        HcExerciseType.ROWING,
        HcExerciseType.ROWING_MACHINE,
        -> SportType.ROWING

        HcExerciseType.YOGA,
        HcExerciseType.PILATES,
        HcExerciseType.STRETCHING,
        -> SportType.MOBILITY

        HcExerciseType.HIGH_INTENSITY_INTERVAL_TRAINING -> SportType.HIIT

        else -> SportType.OTHER
    }

    /** True when [title] contains "match" or "spiel", case- and position-insensitive. */
    fun isMatchTitle(title: String?): Boolean {
        val lower = title?.lowercase() ?: return false
        return MATCH_WORDS.any { lower.contains(it) }
    }
}
