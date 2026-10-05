package com.myhealth.ui.calendar

import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.MealLogSummary
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType

/** Minimal [CalendarDay] parts for the `ui/calendar` unit tests (P3.4/P3.5). */
internal object CalendarUiFixtures {

    const val NOW: Long = 1_757_000_000_000L
    const val DAY: Long = 20_710L

    fun occurrence(day: Long = DAY, id: Long = 1L): EventOccurrence = EventOccurrence(
        eventId = id,
        occurrenceDay = day,
        type = EventType.SOCCER_TRAINING,
        effectiveTitle = "Training",
        effectiveStartMinuteOfDay = 18 * 60,
        effectiveDurationMin = 90,
        isOverride = false,
        linkedActivityId = null,
        sportType = SportType.SOCCER_TRAINING,
        targetDistanceMeters = null,
        isKeyEvent = false,
    )

    fun planned(day: Long = DAY, id: Long = 1L): PlannedSession = PlannedSession(
        id = id,
        planId = null,
        day = day,
        startMinuteOfDay = 7 * 60,
        sportType = SportType.RUN_OUTDOOR,
        sessionType = SessionType.EASY_RUN,
        intensity = Intensity.LOW,
        targetDurationMin = 45,
        targetDistanceMeters = 8000.0,
        targetPaceSecPerKm = null,
        estimatedTrimp = 70.0,
        description = null,
        rationale = null,
        status = PlannedStatus.PLANNED,
        locked = false,
        linkedActivityId = null,
        sourceSuggestionId = null,
        createdAtMillis = NOW,
        updatedAtMillis = NOW,
    )

    fun activity(day: Long = DAY, id: Long = 1L): ActivitySummary = ActivitySummary(
        id = id,
        startAtMillis = NOW,
        endAtMillis = NOW + 2_880_000L,
        day = day,
        sportType = SportType.RUN_OUTDOOR,
        sportGroup = SportType.RUN_OUTDOOR.group,
        title = "Morning run",
        durationSec = 2880,
        elapsedSec = 2880,
        distanceMeters = 8320.0,
        activeEnergyKcal = null,
        totalEnergyKcal = null,
        avgHr = 142,
        maxHr = null,
        avgSpeedMps = null,
        maxSpeedMps = null,
        avgCadenceSpm = null,
        elevationGainM = null,
        trimp = 108.1,
        loadMethod = null,
        rpe = null,
        note = null,
        primarySource = ActivitySource.HEALTH_CONNECT,
        mergedSources = listOf(ActivitySource.HEALTH_CONNECT),
        hasStreams = false,
    )

    fun meal(day: Long = DAY, id: Long = 1L, kcal: Double = 600.0): MealLogSummary = MealLogSummary(
        id = id,
        day = day,
        atMinuteOfDay = 8 * 60,
        slot = MealSlot.BREAKFAST,
        name = "Oats",
        totals = MacroTotals(kcal, 30.0, 70.0, 15.0, 8.0, 12.0, 3.0, 0.5),
    )

    fun target(day: Long = DAY, kcal: Int = 2000): NutritionTarget = NutritionTarget(
        day = day,
        kcal = kcal,
        proteinG = 150,
        carbsG = 220,
        fatG = 70,
        fiberG = 30,
        sugarCapG = 60,
        satFatCapG = 25,
        saltG = 6.0,
        waterMl = 2500,
        bmrKcal = 1700,
        tdeeKcal = 2400,
        dayType = DayType.TRAINING,
        explanation = "fixture",
        warnings = emptyList(),
        inputsHash = "hash",
        computedAtMillis = NOW,
    )

    fun intake(kcal: Double, proteinG: Double = 0.0, carbsG: Double = 0.0, fatG: Double = 0.0): MacroTotals =
        MacroTotals(kcal, proteinG, carbsG, fatG, 0.0, 0.0, 0.0, 0.0)

    fun day(
        day: Long = DAY,
        events: List<EventOccurrence> = emptyList(),
        planned: List<PlannedSession> = emptyList(),
        activities: List<ActivitySummary> = emptyList(),
        meals: List<MealLogSummary> = emptyList(),
        target: NutritionTarget? = null,
        intake: MacroTotals = MacroTotals.ZERO,
    ): CalendarDay = CalendarDay.empty(day).copy(
        events = events,
        planned = planned,
        activities = activities,
        meals = meals,
        target = target,
        intake = intake,
    )
}
