package com.myhealth.domain.engine.nutrition

import com.myhealth.domain.engine.cycle.CycleEngine
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.CycleEntry
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.DailyHealthSummary
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SportType
import com.myhealth.testutil.Fixtures
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.minus

/**
 * Builders for the nutrition target tests (PLAN §3.1.8). "Today" is fixed at 2026-09-12 and the
 * reference athlete is the male 30 y / 180 cm / 80 kg of `nut01`, so every named case differs from
 * the others in exactly the field it is about.
 */
internal object NutritionFixtures {

    const val NOW_MILLIS: Long = 1_757_000_000_000L

    /** The day every case computes a target for. */
    val TODAY: LocalDate = LocalDate.parse("2026-09-12")

    /** A birthday that makes the person exactly [age] years old on [TODAY]. */
    fun birthdayFor(age: Int): Long = TODAY.minus(age, kotlinx.datetime.DateTimeUnit.YEAR).toEpochDays()

    fun profile(
        sex: Sex = Sex.MALE,
        ageYears: Int = 30,
        heightCm: Double = 180.0,
        neatLevel: NeatLevel = NeatLevel.DESK,
        goalWeightKg: Double? = 80.0,
        goalPaceKgPerWeek: Double = 0.0,
        fallbackWeightKg: Double? = null,
    ): Profile = Profile(
        displayName = "Test",
        sex = sex,
        birthDay = birthdayFor(ageYears),
        heightCm = heightCm,
        neatLevel = neatLevel,
        goalWeightKg = goalWeightKg,
        goalPaceKgPerWeek = goalPaceKgPerWeek,
        fallbackWeightKg = fallbackWeightKg,
        createdAtMillis = NOW_MILLIS,
        updatedAtMillis = NOW_MILLIS,
    )

    fun weight(weightKg: Double = 80.0, bodyFatPercent: Double? = null, day: LocalDate = TODAY) =
        BodyMeasurement(
            id = 1L,
            measuredAtMillis = day.atStartOfDayIn(Fixtures.TZ).toEpochMilliseconds(),
            day = day.toEpochDays(),
            weightKg = weightKg,
            bodyFatPercent = bodyFatPercent,
            muscleMassKg = null,
            boneMassKg = null,
            bodyWaterPercent = null,
            source = ActivitySource.MANUAL,
        )

    fun summary(
        day: LocalDate = TODAY,
        totalEnergyKcal: Double? = null,
        activeEnergyKcal: Double? = null,
    ) = DailyHealthSummary(
        day = day.toEpochDays(),
        steps = null,
        totalEnergyKcal = totalEnergyKcal,
        activeEnergyKcal = activeEnergyKcal,
        restingHr = null,
        distanceMeters = null,
        floors = null,
        avgSpo2Percent = null,
        avgRespiratoryRate = null,
        hrvRmssdMs = null,
        vo2Max = null,
        bodyBattery = null,
        stressAvg = null,
        trainingReadiness = null,
        source = ActivitySource.HEALTH_CONNECT,
        updatedAtMillis = NOW_MILLIS,
    )

    fun activity(
        id: Long = 1L,
        sportType: SportType = SportType.RUN_OUTDOOR,
        durationMin: Int = 60,
        startMinuteOfDay: Int = 18 * 60,
        day: LocalDate = TODAY,
        activeEnergyKcal: Double? = null,
        distanceMeters: Double? = null,
        avgSpeedMps: Double? = null,
        trimp: Double? = null,
        avgPowerW: Int? = null,
    ): ActivitySummary {
        val start = day.atStartOfDayIn(Fixtures.TZ).toEpochMilliseconds() +
            startMinuteOfDay * 60_000L
        return ActivitySummary(
            id = id,
            startAtMillis = start,
            endAtMillis = start + durationMin * 60_000L,
            day = day.toEpochDays(),
            sportType = sportType,
            sportGroup = sportType.group,
            title = null,
            durationSec = durationMin * 60,
            elapsedSec = durationMin * 60,
            distanceMeters = distanceMeters,
            activeEnergyKcal = activeEnergyKcal,
            totalEnergyKcal = null,
            avgHr = null,
            maxHr = null,
            avgSpeedMps = avgSpeedMps,
            maxSpeedMps = null,
            avgCadenceSpm = null,
            elevationGainM = null,
            avgPowerW = avgPowerW,
            trimp = trimp,
            loadMethod = null,
            rpe = null,
            note = null,
            primarySource = ActivitySource.HEALTH_CONNECT,
            mergedSources = listOf(ActivitySource.HEALTH_CONNECT),
            hasStreams = false,
        )
    }

    fun planned(
        id: Long = 1L,
        day: LocalDate = TODAY,
        sportType: SportType = SportType.RUN_OUTDOOR,
        sessionType: SessionType = SessionType.EASY_RUN,
        targetDurationMin: Int? = 60,
        startMinuteOfDay: Int? = 18 * 60,
        targetPaceSecPerKm: Int? = null,
        targetDistanceMeters: Double? = null,
        estimatedTrimp: Double? = null,
        linkedActivityId: Long? = null,
        status: PlannedStatus = PlannedStatus.PLANNED,
    ): PlannedSession = PlannedSession(
        id = id,
        planId = null,
        day = day.toEpochDays(),
        startMinuteOfDay = startMinuteOfDay,
        sportType = sportType,
        sessionType = sessionType,
        intensity = Intensity.MODERATE,
        targetDurationMin = targetDurationMin,
        targetDistanceMeters = targetDistanceMeters,
        targetPaceSecPerKm = targetPaceSecPerKm,
        estimatedTrimp = estimatedTrimp,
        description = null,
        rationale = null,
        status = status,
        locked = false,
        linkedActivityId = linkedActivityId,
        sourceSuggestionId = null,
        createdAtMillis = NOW_MILLIS,
        updatedAtMillis = NOW_MILLIS,
    )

    fun occurrence(
        day: LocalDate,
        type: EventType,
        eventId: Long = 1L,
        targetDistanceMeters: Double? = null,
    ): EventOccurrence = EventOccurrence(
        eventId = eventId,
        occurrenceDay = day.toEpochDays(),
        type = type,
        effectiveTitle = type.name,
        effectiveStartMinuteOfDay = 19 * 60,
        effectiveDurationMin = 90,
        isOverride = false,
        linkedActivityId = null,
        sportType = null,
        targetDistanceMeters = targetDistanceMeters,
        isKeyEvent = true,
    )

    /** The `nut01` input; every named case copies it and changes one thing. */
    fun input(
        profile: Profile = profile(),
        latestWeight: BodyMeasurement? = weight(),
        latestBodyFat: BodyMeasurement? = null,
        summary: DailyHealthSummary? = null,
        completed: List<ActivitySummary> = emptyList(),
        planned: List<PlannedSession> = emptyList(),
        dayType: DayType = DayType.REST,
        isDayComplete: Boolean = false,
        date: LocalDate = TODAY,
        cycleStatus: CycleStatus? = null,
    ): NutritionTargetInput = NutritionTargetInput(
        date = date,
        profile = profile,
        latestWeight = latestWeight,
        latestBodyFat = latestBodyFat,
        actualDailySummary = summary,
        completedSessions = completed,
        plannedSessions = planned,
        dayType = dayType,
        isDayComplete = isDayComplete,
        cycleStatus = cycleStatus,
    )

    /**
     * The [CycleStatus] of [TODAY] for a cycle whose period started [offset] days ago, derived by
     * the real [com.myhealth.domain.engine.cycle.CycleEngine] (P11.2). `offset = 20` puts the day
     * in the luteal phase of a default 28-day cycle.
     */
    fun cycleStatus(offset: Long): CycleStatus = checkNotNull(
        CycleEngine.statusFor(
            day = TODAY.toEpochDays(),
            entries = listOf(
                CycleEntry(
                    id = 1L,
                    periodStartDay = TODAY.toEpochDays() - offset,
                    createdAtMillis = NOW_MILLIS,
                    updatedAtMillis = NOW_MILLIS,
                ),
            ),
        ),
    )

    /** The engine under test, pinned to the UTC test zone. */
    fun engine(): NutritionTargetEngine = NutritionTargetEngine(Fixtures.TZ)
}
