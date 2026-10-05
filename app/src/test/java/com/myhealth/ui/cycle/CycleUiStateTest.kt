package com.myhealth.ui.cycle

import com.myhealth.resources.*
import com.myhealth.domain.model.CyclePhase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Pure-helper tests for [CycleUiState] (PLAN §5 P11.3). */
class CycleUiStateTest {

    @Test
    fun dayOfCycleLabel_singleDigitDay() {
        assertEquals("Day 1 of ~28", dayOfCycleLabel(1, 28))
    }

    @Test
    fun dayOfCycleLabel_doubleDigitDay() {
        assertEquals("Day 12 of ~28", dayOfCycleLabel(12, 28))
    }

    @Test
    fun countdownLabel_futureDate_usesPluralDays() {
        val today = LocalDate.of(2026, 9, 14).toEpochDay()
        val next = LocalDate.of(2026, 9, 19).toEpochDay()
        assertEquals("Next period in 5 days (19 Sep)", countdownLabel(today, next))
    }

    @Test
    fun countdownLabel_oneDayAway_usesSingularDay() {
        val today = LocalDate.of(2026, 9, 14).toEpochDay()
        val next = LocalDate.of(2026, 9, 15).toEpochDay()
        assertEquals("Next period in 1 day (15 Sep)", countdownLabel(today, next))
    }

    @Test
    fun countdownLabel_todayIsThePredictedStart() {
        val today = LocalDate.of(2026, 9, 14).toEpochDay()
        assertEquals("Period expected today (14 Sep)", countdownLabel(today, today))
    }

    @Test
    fun countdownLabel_pastDate_reportsLate() {
        val today = LocalDate.of(2026, 9, 20).toEpochDay()
        val next = LocalDate.of(2026, 9, 18).toEpochDay()
        assertEquals("Period is 2 days late (was expected 18 Sep)", countdownLabel(today, next))
    }

    @Test
    fun phaseLabelRes_mapsEveryPhase() {
        assertEquals(Res.string.cycle_phase_menstrual, phaseLabelRes(CyclePhase.MENSTRUAL))
        assertEquals(Res.string.cycle_phase_follicular, phaseLabelRes(CyclePhase.FOLLICULAR))
        assertEquals(Res.string.cycle_phase_ovulation, phaseLabelRes(CyclePhase.OVULATION))
        assertEquals(Res.string.cycle_phase_luteal, phaseLabelRes(CyclePhase.LUTEAL))
    }

    @Test
    fun historyRows_mostRecentFirst_withCycleLengthToNextStart() {
        val entries = listOf(
            com.myhealth.domain.model.CycleEntry(id = 1, periodStartDay = 100, periodEndDay = 104, createdAtMillis = 0, updatedAtMillis = 0),
            com.myhealth.domain.model.CycleEntry(id = 2, periodStartDay = 128, periodEndDay = 132, createdAtMillis = 0, updatedAtMillis = 0),
        )
        val rows = historyRows(entries)
        assertEquals(2, rows.size)
        assertEquals(128L, rows.first().entry.periodStartDay)
        assertEquals(null, rows.first().cycleLengthDays)
        assertEquals(100L, rows[1].entry.periodStartDay)
        assertEquals(28, rows[1].cycleLengthDays)
    }

    @Test
    fun ovulationLabel_formatsDate() {
        val ovulation = LocalDate.of(2026, 9, 20).toEpochDay()
        assertEquals("Ovulation ~20 Sep", ovulationLabel(ovulation))
    }

    @Test
    fun fertileWindowLabel_formatsRange() {
        val start = LocalDate.of(2026, 9, 15).toEpochDay()
        val end = LocalDate.of(2026, 9, 21).toEpochDay()
        assertEquals("Fertile window 15 Sep – 21 Sep", fertileWindowLabel(start..end))
    }
}
