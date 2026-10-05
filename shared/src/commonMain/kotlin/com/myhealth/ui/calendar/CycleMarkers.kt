package com.myhealth.ui.calendar

import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.CycleStatus

/**
 * The four calendar treatments PLAN §5 P11.3 asks for: a logged period day (solid tint), a
 * predicted period day (light tint), the ovulation day (ring/dot) and a fertile-window day (dot).
 * At most one per cell, in that priority order — a day is never more than one of these at once in
 * practice (ovulation always falls inside its own fertile window, but the ring wins).
 */
enum class CycleDayMarker { LOGGED_PERIOD, PREDICTED_PERIOD, OVULATION, FERTILE }

/**
 * Which [CycleDayMarker], if any, a day's already-computed [CycleStatus] gets. Pure — unit-tested
 * in `CycleMarkersTest`. `null` input (no cycle data for that day) yields no marker.
 */
fun cycleDayMarker(status: CycleStatus?): CycleDayMarker? {
    if (status == null) return null
    return when {
        status.isMenstrual && !status.isPredicted -> CycleDayMarker.LOGGED_PERIOD
        status.isMenstrual -> CycleDayMarker.PREDICTED_PERIOD
        status.phase == CyclePhase.OVULATION -> CycleDayMarker.OVULATION
        status.isFertile -> CycleDayMarker.FERTILE
        else -> null
    }
}
