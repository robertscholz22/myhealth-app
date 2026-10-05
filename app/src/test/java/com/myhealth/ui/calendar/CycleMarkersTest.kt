package com.myhealth.ui.calendar

import com.myhealth.domain.model.CycleConfidence
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.CycleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [cycleDayMarker] (PLAN §5 P11.3): which calendar-cell marker a day's [CycleStatus] gets. */
class CycleMarkersTest {

    private fun status(
        phase: CyclePhase,
        isPredicted: Boolean = false,
        fertileWindow: ClosedRange<Long> = 0L..0L,
        day: Long = 0L,
    ) = CycleStatus(
        day = day,
        dayOfCycle = 1,
        phase = phase,
        isLateLuteal = false,
        isPredicted = isPredicted,
        cycleLengthDays = 28,
        periodLengthDays = 5,
        nextPeriodStart = 100L,
        ovulationDay = 86L,
        fertileWindow = fertileWindow,
        confidence = CycleConfidence.MEDIUM,
    )

    @Test
    fun nullStatus_noMarker() {
        assertNull(cycleDayMarker(null))
    }

    @Test
    fun loggedMenstrualDay_isLoggedPeriod() {
        val result = cycleDayMarker(status(CyclePhase.MENSTRUAL, isPredicted = false))
        assertEquals(CycleDayMarker.LOGGED_PERIOD, result)
    }

    @Test
    fun predictedMenstrualDay_isPredictedPeriod() {
        val result = cycleDayMarker(status(CyclePhase.MENSTRUAL, isPredicted = true))
        assertEquals(CycleDayMarker.PREDICTED_PERIOD, result)
    }

    @Test
    fun ovulationPhase_isOvulationMarker() {
        val result = cycleDayMarker(status(CyclePhase.OVULATION))
        assertEquals(CycleDayMarker.OVULATION, result)
    }

    @Test
    fun fertileFollicularDay_isFertileMarker() {
        val result = cycleDayMarker(status(CyclePhase.FOLLICULAR, fertileWindow = 0L..5L, day = 3L))
        assertEquals(CycleDayMarker.FERTILE, result)
    }

    @Test
    fun lutealDayOutsideFertileWindow_noMarker() {
        val result = cycleDayMarker(status(CyclePhase.LUTEAL, fertileWindow = 0L..5L, day = 20L))
        assertNull(result)
    }
}
