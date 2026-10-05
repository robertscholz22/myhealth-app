package com.myhealth.ui.calendar

import com.myhealth.domain.engine.calendar.LinkProposal
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealLogSummary
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.util.epochDayDate
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.displayName
import com.myhealth.ui.common.fmtDecimal
import com.myhealth.ui.common.zeroPad
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** ViewModel state for [DayDetailScreen] (PLAN §4.2 Day detail, P3.5). */
data class DayDetailUiState(
    val day: Long = 0L,
    val isLoading: Boolean = true,
    val data: CalendarDay = CalendarDay.empty(day),
    /** Event id awaiting the delete confirmation dialog, or `null`. */
    val pendingDeleteEventId: Long? = null,
    /** The occurrence whose [LinkActivitySheet] is open, or `null` (P3.7). */
    val linkSheetOccurrence: EventOccurrence? = null,
    /** Auto-link suggestions for the shown day, fed to [LinkActivitySheet] (P3.7). */
    val linkProposals: List<LinkProposal> = emptyList(),
    /** One-shot informational text shown in a snackbar. */
    val message: UiMessage? = null,
    /** P11.3: whether the "Cycle" line should show at all. */
    val cycleTrackingEnabled: Boolean = false,
    /** This day's cycle status, or `null` while tracking is off or nothing is logged yet. */
    val cycleStatus: CycleStatus? = null,
) {
    val date: LocalDate get() = day.epochDayDate()

    val title: String get() = fullDateTitle(date)

    val isEmpty: Boolean
        get() = !isLoading &&
            data.events.isEmpty() && data.planned.isEmpty() && data.activities.isEmpty() &&
            data.meals.isEmpty() && data.target == null && data.load == null && data.sleep == null
}

/** One "target vs intake" row: label, `"1850 / 2230 kcal"` and the bar fill in `[0, 1]`. */
data class MacroProgressRow(
    val label: String,
    val valueLabel: String,
    val fraction: Float,
)

/**
 * Bar fill for one nutrient: `current / target`, clamped to `[0, 1]`; `0` when there is no usable
 * target. Pure — unit-tested in `DayDetailUiStateTest`.
 */
fun progressFraction(current: Double, target: Double?): Float {
    if (target == null || target <= 0.0) return 0f
    return (current / target).coerceIn(0.0, 1.0).toFloat()
}

/**
 * The kcal + macro rows of the "Targets vs intake" section, or an empty list when the day has no
 * target snapshot yet (the screen then shows its "no target yet" state). Pure — unit-tested.
 */
fun targetProgressRows(target: NutritionTarget?, intake: MacroTotals): List<MacroProgressRow> {
    if (target == null) return emptyList()
    return listOf(
        progressRow("Energy", intake.kcal, target.kcal.toDouble(), "kcal"),
        progressRow("Protein", intake.proteinG, target.proteinG.toDouble(), "g"),
        progressRow("Carbs", intake.carbsG, target.carbsG.toDouble(), "g"),
        progressRow("Fat", intake.fatG, target.fatG.toDouble(), "g"),
    )
}

private fun progressRow(label: String, current: Double, target: Double, unit: String) = MacroProgressRow(
    label = label,
    valueLabel = "${fmtDecimal(current, 0)} / ${fmtDecimal(target, 0)} $unit",
    fraction = progressFraction(current, target),
)

/**
 * The "Linked to …" line of an event row (POLISH-7). The day's own activities are already in
 * [CalendarDay], so the link resolves to the activity's title plus its start time and duration —
 * e.g. `Linked: Spiel, 15:00 \u00b7 1h 35m` — instead of the opaque `#11`. Falls back to the id
 * when the linked activity is not on this day (a link across midnight). Pure — unit-tested.
 */
fun linkedActivityLabel(
    activityId: Long,
    activities: List<ActivitySummary>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): UiMessage {
    val activity = activities.firstOrNull { it.id == activityId }
        ?: return UiMessage.of(Res.string.daydetail_linked_activity_fallback, activityId)
    val name = activity.title?.trim()?.ifEmpty { null } ?: activity.sportType.displayName()
    val time = Instant.fromEpochMilliseconds(activity.startAtMillis).toLocalDateTime(zone).time
    val clock = "${time.hour.zeroPad(2)}:${time.minute.zeroPad(2)}"
    return UiMessage.of(Res.string.daydetail_linked_activity_label, name, clock, shortDuration(activity.durationSec))
}

/** "1h 35m", or "54 min" below the hour. */
fun shortDuration(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
}

/** "7h 42m" from a sleep duration in minutes. */
fun formatSleepDuration(totalMin: Int): String = "${totalMin / 60}h ${(totalMin % 60).zeroPad(2)}m"

/**
 * A fully populated day used by the `@Preview`s of the calendar screens (P3.4/P3.5 acceptance).
 * Kept here, next to the state it fills, so no screen file carries a 60-line fixture.
 */
internal fun previewCalendarDay(day: Long): CalendarDay = CalendarDay(
    day = day,
    events = listOf(
        EventOccurrence(
            eventId = 1,
            occurrenceDay = day,
            type = EventType.SOCCER_MATCH,
            effectiveTitle = "League match vs Rovers",
            effectiveStartMinuteOfDay = 19 * 60 + 30,
            effectiveDurationMin = 100,
            isOverride = false,
            linkedActivityId = 11,
            sportType = SportType.SOCCER_MATCH,
            targetDistanceMeters = null,
            isKeyEvent = true,
        ),
        EventOccurrence(
            eventId = 2,
            occurrenceDay = day,
            type = EventType.APPOINTMENT,
            effectiveTitle = "Physio",
            effectiveStartMinuteOfDay = null,
            effectiveDurationMin = null,
            isOverride = true,
            linkedActivityId = null,
            sportType = null,
            targetDistanceMeters = null,
            isKeyEvent = false,
        ),
    ),
    planned = listOf(
        PlannedSession(
            id = 5,
            planId = 1,
            day = day,
            startMinuteOfDay = 7 * 60,
            sportType = SportType.RUN_OUTDOOR,
            sessionType = SessionType.EASY_RUN,
            intensity = Intensity.LOW,
            targetDurationMin = 45,
            targetDistanceMeters = 8000.0,
            targetPaceSecPerKm = 330,
            estimatedTrimp = 70.0,
            description = "Easy aerobic run",
            rationale = null,
            status = PlannedStatus.PLANNED,
            locked = false,
            linkedActivityId = null,
            sourceSuggestionId = null,
            createdAtMillis = 1_757_000_000_000L,
            updatedAtMillis = 1_757_000_000_000L,
        ),
    ),
    activities = listOf(
        ActivitySummary(
            id = 11,
            startAtMillis = 1_757_000_000_000L,
            endAtMillis = 1_757_003_600_000L,
            day = day,
            sportType = SportType.RUN_OUTDOOR,
            sportGroup = SportGroup.RUN,
            title = "Morning run",
            durationSec = 2880,
            elapsedSec = 3000,
            distanceMeters = 8320.0,
            activeEnergyKcal = 540.0,
            totalEnergyKcal = 640.0,
            avgHr = 142,
            maxHr = 168,
            avgSpeedMps = 2.89,
            maxSpeedMps = 4.1,
            avgCadenceSpm = 172.0,
            elevationGainM = 45.0,
            trimp = 108.1,
            loadMethod = LoadMethod.HR_SAMPLES,
            rpe = 5,
            note = null,
            primarySource = ActivitySource.HEALTH_CONNECT,
            mergedSources = listOf(ActivitySource.HEALTH_CONNECT, ActivitySource.FIT_IMPORT),
            hasStreams = true,
        ),
    ),
    meals = listOf(
        MealLogSummary(
            id = 21,
            day = day,
            atMinuteOfDay = 8 * 60,
            slot = MealSlot.BREAKFAST,
            name = "Oats and berries",
            totals = MacroTotals(520.0, 28.0, 72.0, 12.0, 9.0, 18.0, 3.0, 0.8),
        ),
        MealLogSummary(
            id = 22,
            day = day,
            atMinuteOfDay = 13 * 60,
            slot = MealSlot.LUNCH,
            name = "Chicken and rice",
            totals = MacroTotals(830.0, 62.0, 96.0, 20.0, 7.0, 6.0, 5.0, 2.1),
        ),
    ),
    target = NutritionTarget(
        day = day,
        kcal = 2830,
        proteinG = 160,
        carbsG = 350,
        fatG = 85,
        fiberG = 35,
        sugarCapG = 70,
        satFatCapG = 28,
        saltG = 6.0,
        waterMl = 3000,
        bmrKcal = 1780,
        tdeeKcal = 2500,
        dayType = DayType.MATCH_DAY,
        explanation = "Match day: +330 kcal for the evening match.",
        warnings = emptyList(),
        inputsHash = "preview",
        computedAtMillis = 1_757_000_000_000L,
    ),
    intake = MacroTotals(1350.0, 90.0, 168.0, 32.0, 16.0, 24.0, 8.0, 2.9),
    load = DailyLoad(
        day = day,
        trimp = 108.1,
        sessionCount = 1,
        atl = 74.0,
        ctl = 61.0,
        acwr = 1.21,
        tsb = -13.0,
        monotony = 1.37,
        strain = 560.0,
        recoveryScore = 72,
        recoveryBand = RecoveryBand.GOOD,
        recoveryConfidence = 0.8,
        flags = listOf("ACWR rising"),
        computedAtMillis = 1_757_000_000_000L,
    ),
    sleep = SleepRecord(
        id = 31,
        startAtMillis = 1_756_950_000_000L,
        endAtMillis = 1_756_977_720_000L,
        night = day,
        totalSleepMin = 462,
        lightMin = 240,
        deepMin = 92,
        remMin = 110,
        awakeMin = 20,
        stages = null,
        source = ActivitySource.HEALTH_CONNECT,
        externalId = null,
        sleepScore = 81,
    ),
)

/** The `LinkMethod` shown on a linked event badge; referenced by the day-detail rows. */
internal fun LinkMethod.displayName(): String = labelOf(name)
