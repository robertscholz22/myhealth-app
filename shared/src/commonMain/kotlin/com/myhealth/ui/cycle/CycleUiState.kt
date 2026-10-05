package com.myhealth.ui.cycle

import com.myhealth.domain.engine.cycle.CycleEngine
import com.myhealth.domain.model.CycleConfidence
import com.myhealth.domain.model.CycleEntry
import com.myhealth.domain.model.CycleForecast
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.util.epochDayDate
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.usText
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource

/** ViewModel state for [CycleScreen] (PLAN §5 P11.3): the current status card, the "Log period
 * start" / "Period ended" dialogs, the history list and the six-cycle forecast. */
data class CycleUiState(
    val isLoading: Boolean = true,
    val trackingEnabled: Boolean = false,
    val today: Long = 0L,
    /** `null` until a first period start is logged (PLAN §5 P11.1 `statusFor`). */
    val status: CycleStatus? = null,
    val forecast: CycleForecast = CycleForecast(),
    /** Every logged entry, in no particular order — see [historyRows] for the display order. */
    val history: List<CycleEntry> = emptyList(),
    val showLogStartDialog: Boolean = false,
    val showPeriodEndedDialog: Boolean = false,
    /** Entry id awaiting the delete confirmation dialog, or `null`. */
    val pendingDeleteId: Long? = null,
    val message: UiMessage? = null,
) {
    val hasEntries: Boolean get() = history.isNotEmpty()

    /** The entry "Period ended" edits: the most recently started one. `null` when there is none. */
    val latestEntry: CycleEntry? get() = history.maxByOrNull { it.periodStartDay }

    /** History rows, most recent period first — see [historyRows]. */
    val historyRows: List<CycleHistoryRow> get() = historyRows(history)

    /** Confidence explanation for the status card — see [confidenceLine]. */
    val confidenceMessage: UiMessage? get() = status?.let { confidenceLine(it.confidence, history) }
}

/** One row of the cycle-screen history list: the entry itself, and — when a later period is
 * logged — the number of days from this start to that one (the observed cycle length). */
data class CycleHistoryRow(val entry: CycleEntry, val cycleLengthDays: Int?)

/**
 * History rows ordered most-recent-first. [CycleHistoryRow.cycleLengthDays] is the gap to the
 * *next* (chronologically later) logged start, so the newest row never has one. Pure —
 * unit-tested in `CycleUiStateTest`.
 */
fun historyRows(entries: List<CycleEntry>): List<CycleHistoryRow> {
    val ascending = entries.sortedBy { it.periodStartDay }
    return ascending
        .mapIndexed { index, entry ->
            val next = ascending.getOrNull(index + 1)
            CycleHistoryRow(entry, next?.let { (it.periodStartDay - entry.periodStartDay).toInt() })
        }
        .sortedByDescending { it.entry.periodStartDay }
}

/** "Day 12 of ~28" — the status card's headline. Pure — unit-tested in `CycleUiStateTest`. */
fun dayOfCycleLabel(dayOfCycle: Int, cycleLengthDays: Int): String =
    "Day $dayOfCycle of ~$cycleLengthDays"

private const val CYCLE_DATE_FORMAT = "d MMM"

/**
 * "Next period in 5 days (20 Sep)", "Period expected today (20 Sep)" on the day itself, or
 * "Period is 2 days late (was expected 20 Sep)" once [today] has passed [nextPeriodStart]. Pure —
 * unit-tested in `CycleUiStateTest`.
 */
fun countdownLabel(today: Long, nextPeriodStart: Long): String {
    val date = nextPeriodStart.epochDayDate().usText(CYCLE_DATE_FORMAT)
    val days = nextPeriodStart - today
    return when {
        days > 0 -> "Next period in $days ${dayWord(days)} ($date)"
        days == 0L -> "Period expected today ($date)"
        else -> "Period is ${-days} ${dayWord(-days)} late (was expected $date)"
    }
}

/** "Ovulation ~20 Sep". Pure — unit-tested in `CycleUiStateTest`. */
fun ovulationLabel(ovulationDay: Long): String = "Ovulation ~${ovulationDay.epochDayDate().usText(CYCLE_DATE_FORMAT)}"

/** "Fertile window 15 Sep – 21 Sep". Pure — unit-tested in `CycleUiStateTest`. */
fun fertileWindowLabel(fertileWindow: ClosedRange<Long>): String {
    val start = fertileWindow.start.epochDayDate().usText(CYCLE_DATE_FORMAT)
    val end = fertileWindow.endInclusive.epochDayDate().usText(CYCLE_DATE_FORMAT)
    return "Fertile window $start – $end"
}

private fun dayWord(days: Long): String = if (days == 1L) "day" else "days"

/** The phase badge text (PLAN §5 P11.3). Pure — unit-tested in `CycleUiStateTest`. */
fun phaseLabelRes(phase: CyclePhase): StringResource = when (phase) {
    CyclePhase.MENSTRUAL -> Res.string.cycle_phase_menstrual
    CyclePhase.FOLLICULAR -> Res.string.cycle_phase_follicular
    CyclePhase.OVULATION -> Res.string.cycle_phase_ovulation
    CyclePhase.LUTEAL -> Res.string.cycle_phase_luteal
}

/**
 * "Based on 3 logged cycles" / "Based on a default 28-day cycle — log your period to improve
 * this" (PLAN §5 P11.3's confidence line). [entries] gives the number of intervals actually
 * averaged — the same count [CycleEngine.confidenceOf] used to decide [confidence].
 */
fun confidenceLine(confidence: CycleConfidence, entries: List<CycleEntry>): UiMessage =
    if (confidence == CycleConfidence.LOW) {
        UiMessage.of(Res.string.cycle_confidence_low)
    } else {
        val logged = CycleEngine.intervals(entries).size
        if (logged == 1) UiMessage.of(Res.string.cycle_confidence_based_on_one) else UiMessage.of(Res.string.cycle_confidence_based_on_many, logged)
    }

/** A fully populated state used by the screen's `@Preview`s. */
internal fun previewCycleUiState(): CycleUiState {
    val today = LocalDate(2026, 9, 14).toEpochDays()
    val start = today - 3
    return CycleUiState(
        isLoading = false,
        trackingEnabled = true,
        today = today,
        status = CycleStatus(
            day = today,
            dayOfCycle = 4,
            phase = CyclePhase.MENSTRUAL,
            isLateLuteal = false,
            isPredicted = false,
            cycleLengthDays = 28,
            periodLengthDays = 5,
            nextPeriodStart = start + 28,
            ovulationDay = start + 28 - 14,
            fertileWindow = (start + 28 - 14 - 5)..(start + 28 - 14 + 1),
            confidence = CycleConfidence.MEDIUM,
        ),
        forecast = CycleForecast(
            cycles = emptyList(),
            cycleLengthDays = 28,
            periodLengthDays = 5,
            confidence = CycleConfidence.MEDIUM,
        ),
        history = listOf(
            CycleEntry(id = 1, periodStartDay = start, periodEndDay = null, createdAtMillis = 0, updatedAtMillis = 0),
            CycleEntry(id = 2, periodStartDay = start - 28, periodEndDay = start - 28 + 4, createdAtMillis = 0, updatedAtMillis = 0),
        ),
    )
}
