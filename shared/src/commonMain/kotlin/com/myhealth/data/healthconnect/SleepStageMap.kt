package com.myhealth.data.healthconnect

import com.myhealth.domain.model.SleepStage

/**
 * `SleepSessionRecord.Stage.stage` → [SleepStage] (PLAN P2.3). Like `ExerciseTypeMap`, only the
 * [HcSleepStageType] names are relied on (pinned to `STAGE_TYPE_*` by `HcConstantsTest`). An unrecognised value degrades to
 * [SleepStage.UNKNOWN] rather than throwing (§2.1 converter rule).
 */
object SleepStageMap {

    fun toSleepStage(stage: Int): SleepStage = when (stage) {
        HcSleepStageType.AWAKE -> SleepStage.AWAKE
        HcSleepStageType.AWAKE_IN_BED -> SleepStage.AWAKE_IN_BED
        HcSleepStageType.OUT_OF_BED -> SleepStage.OUT_OF_BED
        HcSleepStageType.SLEEPING -> SleepStage.SLEEPING
        HcSleepStageType.LIGHT -> SleepStage.LIGHT
        HcSleepStageType.DEEP -> SleepStage.DEEP
        HcSleepStageType.REM -> SleepStage.REM
        else -> SleepStage.UNKNOWN
    }

    /** Stages that count towards `sleep_session.totalSleepMin` — everything except time awake. */
    fun isAsleep(stage: SleepStage): Boolean = when (stage) {
        SleepStage.SLEEPING, SleepStage.LIGHT, SleepStage.DEEP, SleepStage.REM -> true
        SleepStage.AWAKE, SleepStage.AWAKE_IN_BED, SleepStage.OUT_OF_BED, SleepStage.UNKNOWN ->
            false
    }
}
