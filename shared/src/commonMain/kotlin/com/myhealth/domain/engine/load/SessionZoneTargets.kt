package com.myhealth.domain.engine.load

import com.myhealth.domain.model.SessionType

/**
 * The heart-rate zone a planned session is meant to be run in (PLAN §3.9).
 *
 * `null` means "no target": a strength session's heart rate says little about the stimulus and
 * soccer is intermittent by nature, so the screens print nothing rather than a number the athlete
 * would have to ignore. `MOBILITY`'s Z1 is advisory only.
 *
 * The range is inclusive on both ends and expressed in zone indices (`1..5`), so a screen can turn
 * it into bpm through [HrZoneModel.rangeOf] and a pace through the P14.2 band table — which takes
 * the **first** zone of the range, the bottom of the training intent.
 */
object SessionZoneTargets {

    fun targetFor(sessionType: SessionType): IntRange? = when (sessionType) {
        SessionType.RECOVERY_RUN -> 1..1
        SessionType.EASY_RUN -> 2..2
        SessionType.LONG_RUN -> 2..2
        SessionType.TEMPO_RUN -> 3..4
        SessionType.INTERVAL_RUN -> 4..5
        SessionType.CROSS_TRAINING -> 2..2
        SessionType.MOBILITY -> 1..1
        SessionType.ENDURANCE_RIDE -> 2..2
        SessionType.BIKE_INTERVALS -> 4..5
        SessionType.TRAINER_SESSION -> 3..3
        SessionType.RECOVERY_SPIN -> 1..1
        SessionType.TIME_TRIAL -> 4..5
        SessionType.STRENGTH_FULL,
        SessionType.STRENGTH_UPPER,
        SessionType.STRENGTH_LOWER,
        SessionType.SOCCER_TRAINING,
        SessionType.SOCCER_MATCH,
        SessionType.REST,
        -> null
    }
}
