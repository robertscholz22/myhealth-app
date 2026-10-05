package com.myhealth.domain.engine.cycle

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.CycleConfidence
import com.myhealth.domain.model.CycleEntry
import com.myhealth.domain.model.CyclePhase
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * [CycleEngine] against the named cases of PLAN §5 P11.1 (`cyc01`–`cyc11`).
 *
 * Every history is built from period **starts** — the only thing the user actually observes — and
 * every assertion is about what the engine derives from them.
 */
class CycleEngineTest {

    /** Day 0 of every fixture; concrete so `dayOfCycle` arithmetic stays readable. */
    private val base: Long = Fixtures.epochDay("2026-01-05")

    private fun entry(startOffset: Long, lengthDays: Int? = null, id: Long = startOffset): CycleEntry =
        CycleEntry(
            id = id,
            periodStartDay = base + startOffset,
            periodEndDay = lengthDays?.let { base + startOffset + it - 1 },
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )

    /** Starts every [interval] days, each period [periodDays] long. */
    private fun history(count: Int, interval: Long = 28L, periodDays: Int? = 5): List<CycleEntry> =
        (0 until count).map { entry(it * interval, periodDays, id = it + 1L) }

    @Test
    fun cyc01_defaults_without_history_28_5() {
        // A single logged start yields no interval and no finished period: both defaults apply.
        val averages = CycleEngine.averages(listOf(entry(0, lengthDays = null)))

        assertThat(averages.cycleLengthDays).isEqualTo(28)
        assertThat(averages.periodLengthDays).isEqualTo(5)
        assertThat(averages.confidence).isEqualTo(CycleConfidence.LOW)

        val status = CycleEngine.statusFor(base, listOf(entry(0, lengthDays = null)))
        assertThat(status).isNotNull()
        assertThat(status!!.dayOfCycle).isEqualTo(1)
        assertThat(status.cycleLengthDays).isEqualTo(28)
        assertThat(status.periodLengthDays).isEqualTo(5)
        assertThat(status.phase).isEqualTo(CyclePhase.MENSTRUAL)
        assertThat(status.isPredicted).isFalse()
    }

    @Test
    fun cyc02_average_cycle_from_intervals() {
        // Starts 0, 30, 60, 91 -> intervals 30, 30, 31 -> mean 30.33 -> 30 (half-up).
        val entries = listOf(entry(0), entry(30), entry(60), entry(91))

        assertThat(CycleEngine.intervals(entries)).containsExactly(30, 30, 31).inOrder()
        assertThat(CycleEngine.averages(entries).cycleLengthDays).isEqualTo(30)
        // Only the newest six intervals count.
        val long = (0..9).map { entry(it * 26L, id = it + 1L) }
        assertThat(CycleEngine.intervals(long)).hasSize(6)
        assertThat(CycleEngine.averages(long).cycleLengthDays).isEqualTo(26)
    }

    @Test
    fun cyc03_clamps_outliers_21_45() {
        val tooLong = listOf(entry(0), entry(60), entry(120))
        assertThat(CycleEngine.intervals(tooLong)).containsExactly(45, 45)
        assertThat(CycleEngine.averages(tooLong).cycleLengthDays).isEqualTo(45)

        val tooShort = listOf(entry(0), entry(10), entry(20))
        assertThat(CycleEngine.intervals(tooShort)).containsExactly(21, 21)
        assertThat(CycleEngine.averages(tooShort).cycleLengthDays).isEqualTo(21)
    }

    @Test
    fun cyc04_period_length_from_ends() {
        val entries = listOf(
            entry(0, lengthDays = 6),
            entry(28, lengthDays = 7),
            entry(56, lengthDays = 6),
            // Still running: no end, so it contributes nothing to the average.
            entry(84, lengthDays = null),
        )

        assertThat(CycleEngine.periodLengths(entries)).containsExactly(6, 7, 6).inOrder()
        // mean 6.33 -> 6
        assertThat(CycleEngine.averages(entries).periodLengthDays).isEqualTo(6)

        // A 30-day period is a mis-log, not a period: clamped to the 10-day maximum.
        assertThat(CycleEngine.periodLengths(listOf(entry(0, lengthDays = 30)))).containsExactly(10)
        assertThat(CycleEngine.periodLengths(listOf(entry(0, lengthDays = 1)))).containsExactly(2)
    }

    @Test
    fun cyc05_phase_boundaries_28_day_cycle() {
        val entries = history(count = 3)
        val cycleStart = base + 56L
        fun phaseOnDay(dayOfCycle: Int) =
            CycleEngine.statusFor(cycleStart + dayOfCycle - 1, entries)!!.phase

        assertThat(CycleEngine.averages(entries).cycleLengthDays).isEqualTo(28)
        assertThat(phaseOnDay(1)).isEqualTo(CyclePhase.MENSTRUAL)
        assertThat(phaseOnDay(5)).isEqualTo(CyclePhase.MENSTRUAL)
        assertThat(phaseOnDay(6)).isEqualTo(CyclePhase.FOLLICULAR)
        assertThat(phaseOnDay(12)).isEqualTo(CyclePhase.FOLLICULAR)
        assertThat(phaseOnDay(13)).isEqualTo(CyclePhase.OVULATION)
        assertThat(phaseOnDay(14)).isEqualTo(CyclePhase.OVULATION)
        assertThat(phaseOnDay(15)).isEqualTo(CyclePhase.OVULATION)
        assertThat(phaseOnDay(16)).isEqualTo(CyclePhase.LUTEAL)

        // Late luteal = the last five days before the predicted next start (24..28).
        fun lateOnDay(dayOfCycle: Int) =
            CycleEngine.statusFor(cycleStart + dayOfCycle - 1, entries)!!.isLateLuteal
        assertThat(lateOnDay(23)).isFalse()
        (24..28).forEach { assertThat(lateOnDay(it)).isTrue() }
        (24..28).forEach { assertThat(phaseOnDay(it)).isEqualTo(CyclePhase.LUTEAL) }
    }

    @Test
    fun cyc06_predicted_cycle_rolls_forward() {
        val entries = history(count = 3)
        val lastStart = base + 56L

        val inside = CycleEngine.statusFor(lastStart + 10, entries)!!
        assertThat(inside.isPredicted).isFalse()
        assertThat(inside.dayOfCycle).isEqualTo(11)

        // 31 days past the last logged start: one whole cycle has rolled over.
        val rolled = CycleEngine.statusFor(lastStart + 31, entries)!!
        assertThat(rolled.isPredicted).isTrue()
        assertThat(rolled.dayOfCycle).isEqualTo(4)
        assertThat(rolled.phase).isEqualTo(CyclePhase.MENSTRUAL)
        assertThat(rolled.nextPeriodStart).isEqualTo(lastStart + 56)

        // Two cycles out it still rolls, and `dayOfCycle` never leaves 1..cycleLength.
        val farOut = CycleEngine.statusFor(lastStart + 60, entries)!!
        assertThat(farOut.isPredicted).isTrue()
        assertThat(farOut.dayOfCycle).isEqualTo(5)
    }

    @Test
    fun cyc07_ovulation_is_14_days_before_next_start() {
        val entries = history(count = 3)
        val status = CycleEngine.statusFor(base + 56L, entries)!!

        assertThat(status.nextPeriodStart).isEqualTo(base + 84L)
        assertThat(status.ovulationDay).isEqualTo(status.nextPeriodStart - 14)
        // The absolute ovulation date always lands inside the three-day OVULATION window.
        assertThat(CycleEngine.statusFor(status.ovulationDay, entries)!!.phase)
            .isEqualTo(CyclePhase.OVULATION)
    }

    @Test
    fun cyc08_fertile_window_minus5_plus1() {
        val entries = history(count = 3)
        val status = CycleEngine.statusFor(base + 56L, entries)!!
        val window = status.fertileWindow

        assertThat(window.start).isEqualTo(status.ovulationDay - 5)
        assertThat(window.endInclusive).isEqualTo(status.ovulationDay + 1)
        assertThat((window.start..window.endInclusive).count()).isEqualTo(7)
        assertThat(status.ovulationDay - 3 in window).isTrue()
        assertThat(status.ovulationDay + 2 in window).isFalse()
    }

    @Test
    fun cyc09_confidence_levels() {
        // No interval at all -> the 28/5 defaults.
        assertThat(CycleEngine.averages(listOf(entry(0))).confidence).isEqualTo(CycleConfidence.LOW)
        assertThat(CycleEngine.averages(emptyList()).confidence).isEqualTo(CycleConfidence.LOW)

        // One or two intervals -> MEDIUM, however regular they are.
        assertThat(CycleEngine.averages(history(count = 2)).confidence).isEqualTo(CycleConfidence.MEDIUM)
        assertThat(CycleEngine.averages(history(count = 3)).confidence).isEqualTo(CycleConfidence.MEDIUM)

        // Three regular intervals -> HIGH (population SD 0 <= 3).
        assertThat(CycleEngine.averages(history(count = 4)).confidence).isEqualTo(CycleConfidence.HIGH)

        // Three irregular ones (21, 28, 35) -> SD 5.7 > 3, so still MEDIUM.
        val irregular = listOf(entry(0), entry(21), entry(49), entry(84))
        assertThat(CycleEngine.intervals(irregular)).containsExactly(21, 28, 35).inOrder()
        assertThat(CycleEngine.populationSd(listOf(21, 28, 35))).isWithin(0.01).of(5.72)
        assertThat(CycleEngine.averages(irregular).confidence).isEqualTo(CycleConfidence.MEDIUM)
    }

    @Test
    fun cyc10_forecast_six_cycles() {
        val entries = history(count = 3)
        val today = base + 60L
        val status = CycleEngine.statusFor(today, entries)!!

        val forecast = CycleEngine.forecast(entries, today)

        assertThat(forecast.cycles).hasSize(6)
        assertThat(forecast.cycleLengthDays).isEqualTo(28)
        assertThat(forecast.periodLengthDays).isEqualTo(5)
        assertThat(forecast.cycles.first().periodStart).isEqualTo(status.nextPeriodStart)
        assertThat(forecast.cycles.map { it.periodStart })
            .containsExactlyElementsIn((0..5).map { status.nextPeriodStart + it * 28L })
            .inOrder()
        forecast.cycles.forEach { cycle ->
            assertThat(cycle.periodEnd).isEqualTo(cycle.periodStart + 4)
            assertThat(cycle.ovulationDay).isEqualTo(cycle.periodStart + 28 - 14)
            assertThat(cycle.fertileWindow.start).isEqualTo(cycle.ovulationDay - 5)
            assertThat(cycle.fertileWindow.endInclusive).isEqualTo(cycle.ovulationDay + 1)
        }
        assertThat(CycleEngine.forecast(entries, today, cycles = 2).cycles).hasSize(2)
    }

    @Test
    fun cyc11_no_entries_null_status() {
        assertThat(CycleEngine.statusFor(base, emptyList())).isNull()
        assertThat(CycleEngine.statusesFor(base, base + 7, emptyList())).isEmpty()
        assertThat(CycleEngine.forecast(emptyList(), base).cycles).isEmpty()
        assertThat(CycleEngine.forecast(emptyList(), base).isEmpty).isTrue()

        // A day before the very first logged start belongs to no cycle either.
        val entries = listOf(entry(10))
        assertThat(CycleEngine.statusFor(base + 9, entries)).isNull()
        assertThat(CycleEngine.statusFor(base + 10, entries)).isNotNull()
    }
}
