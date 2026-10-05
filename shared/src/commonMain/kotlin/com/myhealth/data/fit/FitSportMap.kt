package com.myhealth.data.fit

import com.myhealth.domain.model.SportType

/**
 * FIT `sport` / `sub_sport` → [SportType] (PLAN P7.2).
 *
 * Both values arrive as FIT profile enum **names** (`"RUNNING"`, `"TREADMILL"`), never as SDK
 * types, so this object stays pure Kotlin and testable from JSON fixtures. Anything unrecognised
 * falls back to [SportType.OTHER] rather than throwing — §2.1's rule for forward-incompatible
 * data applies to imported files just as much as to stored enums.
 */
object FitSportMap {

    fun toSportType(sport: String?, subSport: String?): SportType {
        val s = sport?.uppercase()
        val sub = subSport?.uppercase()
        return when (s) {
            "RUNNING" -> runningType(sub)
            "CYCLING", "E_BIKING" -> if (sub in INDOOR_CYCLING_SUBS) {
                SportType.CYCLING_INDOOR
            } else {
                SportType.CYCLING
            }
            "SOCCER" -> SportType.SOCCER_TRAINING
            "TRAINING" -> trainingType(sub)
            "FITNESS_EQUIPMENT" -> equipmentType(sub)
            "WALKING" -> SportType.WALK
            "HIKING", "MOUNTAINEERING" -> SportType.HIKE
            "SWIMMING" -> SportType.SWIM
            "ROWING" -> SportType.ROWING
            else -> SportType.OTHER
        }
    }

    private fun runningType(sub: String?): SportType = when (sub) {
        "TREADMILL", "INDOOR_RUNNING", "VIRTUAL_ACTIVITY" -> SportType.RUN_TREADMILL
        "TRAIL", "ULTRA" -> SportType.RUN_TRAIL
        "TRACK" -> SportType.RUN_TRACK
        else -> SportType.RUN_OUTDOOR
    }

    /**
     * `Sport.TRAINING` is Garmin's "Gym & Fitness" family. The plan maps its strength sub-sports
     * to [SportType.STRENGTH]; the cardio and flexibility ones have their own domain types, and a
     * generic/absent sub-sport keeps the family default of strength.
     */
    private fun trainingType(sub: String?): SportType = when (sub) {
        "CARDIO_TRAINING", "HIIT", "AMRAP", "EMOM", "TABATA" -> SportType.HIIT
        "FLEXIBILITY_TRAINING", "YOGA", "PILATES", "BREATHING" -> SportType.MOBILITY
        else -> SportType.STRENGTH
    }

    private fun equipmentType(sub: String?): SportType = when (sub) {
        "TREADMILL", "INDOOR_RUNNING" -> SportType.RUN_TREADMILL
        "SPIN", "INDOOR_CYCLING" -> SportType.CYCLING_INDOOR
        "INDOOR_ROWING" -> SportType.ROWING
        "INDOOR_WALKING" -> SportType.WALK
        "STRENGTH_TRAINING" -> SportType.STRENGTH
        "CARDIO_TRAINING", "HIIT" -> SportType.HIIT
        "FLEXIBILITY_TRAINING", "YOGA", "PILATES" -> SportType.MOBILITY
        else -> SportType.OTHER
    }

    private val INDOOR_CYCLING_SUBS = setOf("INDOOR_CYCLING", "SPIN", "VIRTUAL_ACTIVITY")
}
