package com.myhealth.domain.engine.suggest

import com.myhealth.domain.engine.cycle.CycleEngine
import com.myhealth.domain.engine.strength.MuscleLoadEngine
import com.myhealth.domain.engine.strength.MuscleLoadInput
import com.myhealth.domain.engine.strength.MuscleLoadState
import com.myhealth.domain.engine.strength.MuscleSession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.CycleEntry
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.RecoveryState
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.testutil.Fixtures
import kotlinx.datetime.LocalDate

/**
 * Builders for the suggestion-engine tests (PLAN §3.5). Every default is deliberately boring so a
 * test only states what it is actually about.
 *
 * "Today" is **Monday 2026-09-14** everywhere, so weekday-sensitive rules (C12's Sat/Sun long run)
 * are readable as day offsets: day +5 is Saturday, day +6 is Sunday.
 */
object SuggestFixtures {

    val TODAY: LocalDate = LocalDate(2026, 9, 14)
    val TODAY_DAY: Long = TODAY.toEpochDays()

    fun day(offset: Long): Long = TODAY_DAY + offset

    fun profile(
        preferredSportsJson: String = "{}",
        mobilityOnRestDays: Boolean = false,
        ftpWattsManual: Int? = null,
        indoorTrainerAvailable: Boolean = false,
    ): Profile = Profile(
        id = 1L,
        displayName = "Robert",
        sex = Sex.MALE,
        birthDay = Fixtures.epochDay("1990-05-20"),
        heightCm = 182.0,
        preferredSportsJson = preferredSportsJson,
        mobilityOnRestDays = mobilityOnRestDays,
        ftpWattsManual = ftpWattsManual,
        indoorTrainerAvailable = indoorTrainerAvailable,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    /** P12.3: the sport caps of an athlete who rides — the `CYCLE` cap is the suggester's gate. */
    /**
     * Onboarding always writes all four sports; P19.6's larger budgets made the missing `SOCCER`
     * key (= uncapped soccer) crowd out the rides these fixtures are about, so it is now explicit.
     */
    fun bikeSportsJson(cycleCap: Int = 3, runCap: Int = 2, strengthCap: Int = 2): String =
        """{"RUN":$runCap,"STRENGTH":$strengthCap,"SOCCER":0,"CYCLE":$cycleCap}"""

    /** P12.3: an active `BIKE_*` goal, the other half of the gate. */
    fun bikeGoal(
        type: GoalType = GoalType.BIKE_FTP,
        targetValue: Double? = 300.0,
        targetDay: Long? = null,
        targetDistanceMeters: Double? = null,
        priority: Int = 1,
        id: Long = 7L,
    ): Goal = goal(
        id = id,
        type = type,
        title = "Bike goal",
        targetDay = targetDay,
        targetDistanceMeters = targetDistanceMeters,
        targetValue = targetValue,
        priority = priority,
    )

    fun load(
        day: Long,
        trimp: Double = 0.0,
        atl: Double = 0.0,
        ctl: Double = 0.0,
        acwr: Double? = null,
    ): DailyLoad = DailyLoad(
        day = day,
        trimp = trimp,
        sessionCount = if (trimp > 0.0) 1 else 0,
        atl = atl,
        ctl = ctl,
        acwr = acwr,
        tsb = ctl - atl,
        monotony = null,
        strain = null,
        recoveryScore = null,
        recoveryBand = null,
        recoveryConfidence = 1.0,
        flags = emptyList(),
        computedAtMillis = 0L,
    )

    /**
     * 42 days of history ending yesterday: every day carries [dailyTrimp], so
     * `lastWeekActual = 7 * dailyTrimp`, and the newest row carries [ctl] / [acwr].
     *
     * P19.6: the fifth week back (days 29–35) is a lighter week at half the load, so the standard
     * athlete took a down week a month ago and the coming week is a normal one. Without it, six
     * flat weeks would make every fixture a `LONG_BUILD` recovery week.
     */
    fun loadHistory(
        todayDay: Long = TODAY_DAY,
        ctl: Double = 40.0,
        dailyTrimp: Double = 40.0,
        acwr: Double? = 1.0,
    ): List<DailyLoad> = (1..42).map { back ->
        val d = todayDay - back
        val trimp = if (back in 29..35) dailyTrimp * 0.5 else dailyTrimp
        load(day = d, trimp = trimp, atl = ctl, ctl = ctl, acwr = if (back == 1) acwr else null)
    }.sortedBy { it.day }

    /**
     * P19.6: one row per day for `weeks.size` rolling weeks ending yesterday; `weeks[0]` is last
     * week's total as a multiple of `7 × ctl`, spread evenly over its seven days. [bandsLastWeek]
     * sets the recovery band of last week's days, newest first.
     */
    fun weeklyHistory(
        weeks: List<Double>,
        ctl: Double = 40.0,
        todayDay: Long = TODAY_DAY,
        bandsLastWeek: List<RecoveryBand?> = emptyList(),
    ): List<DailyLoad> = weeks.flatMapIndexed { index, ratio ->
        (1..7).map { dayInWeek ->
            val back = index * 7 + dayInWeek
            load(day = todayDay - back, trimp = ctl * ratio, atl = ctl, ctl = ctl)
                .copy(recoveryBand = if (index == 0) bandsLastWeek.getOrNull(dayInWeek - 1) else null)
        }
    }.sortedBy { it.day }

    fun recovery(band: RecoveryBand?, score: Int? = 70, day: Long = TODAY_DAY): RecoveryState =
        RecoveryState(
            day = day,
            score = score,
            band = band,
            confidence = 1.0,
            components = emptyList(),
            flags = emptyList(),
            warnings = emptyList(),
        )

    fun event(
        day: Long,
        type: EventType,
        eventId: Long = day,
        durationMin: Int? = null,
        sportType: SportType? = null,
    ): EventOccurrence = EventOccurrence(
        eventId = eventId,
        occurrenceDay = day,
        type = type,
        effectiveTitle = type.name,
        effectiveStartMinuteOfDay = 600,
        effectiveDurationMin = durationMin,
        isOverride = false,
        linkedActivityId = null,
        sportType = sportType,
        targetDistanceMeters = null,
        isKeyEvent = type == EventType.RACE,
    )

    fun raceGoal(
        targetDay: Long,
        targetTimeSec: Int = 1200,
        distanceMeters: Double = 5000.0,
        priority: Int = 1,
        id: Long = 1L,
    ): Goal = goal(
        id = id,
        type = GoalType.RACE_TIME,
        title = "5k in 20:00",
        targetDay = targetDay,
        targetDistanceMeters = distanceMeters,
        targetTimeSec = targetTimeSec,
        priority = priority,
    )

    @Suppress("LongParameterList")
    fun goal(
        id: Long = 1L,
        type: GoalType = GoalType.RACE_TIME,
        title: String = "Goal",
        targetDay: Long? = null,
        targetDistanceMeters: Double? = null,
        targetTimeSec: Int? = null,
        targetWeightKg: Double? = null,
        targetValue: Double? = null,
        priority: Int = 1,
        status: GoalStatus = GoalStatus.ACTIVE,
        createdAtMillis: Long = 0L,
    ): Goal = Goal(
        id = id,
        type = type,
        title = title,
        targetDay = targetDay,
        targetDistanceMeters = targetDistanceMeters,
        targetTimeSec = targetTimeSec,
        targetWeightKg = targetWeightKg,
        targetValue = targetValue,
        priority = priority,
        status = status,
        linkedEventId = null,
        notes = null,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = createdAtMillis,
    )

    fun locked(
        day: Long,
        sessionType: SessionType = SessionType.EASY_RUN,
        sportType: SportType = SportType.RUN_OUTDOOR,
        intensity: Intensity = Intensity.LOW,
        minutes: Int? = 45,
        estimatedTrimp: Double? = 54.0,
        id: Long = day,
    ): PlannedSession = PlannedSession(
        id = id,
        planId = null,
        day = day,
        startMinuteOfDay = 420,
        sportType = sportType,
        sessionType = sessionType,
        intensity = intensity,
        targetDurationMin = minutes,
        targetDistanceMeters = null,
        targetPaceSecPerKm = null,
        estimatedTrimp = estimatedTrimp,
        description = null,
        rationale = null,
        status = PlannedStatus.PLANNED,
        locked = true,
        linkedActivityId = null,
        sourceSuggestionId = null,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    fun activity(
        day: Long,
        trimp: Double,
        sportType: SportType = SportType.SOCCER_MATCH,
        id: Long = day,
        durationSec: Int = 5400,
    ): ActivitySummary = ActivitySummary(
        id = id,
        startAtMillis = day * 86_400_000L,
        endAtMillis = day * 86_400_000L + durationSec * 1000L,
        day = day,
        sportType = sportType,
        sportGroup = sportType.group,
        title = null,
        durationSec = durationSec,
        elapsedSec = durationSec,
        distanceMeters = null,
        activeEnergyKcal = null,
        totalEnergyKcal = null,
        avgHr = null,
        maxHr = null,
        avgSpeedMps = null,
        maxSpeedMps = null,
        avgCadenceSpm = null,
        elevationGainM = null,
        trimp = trimp,
        loadMethod = null,
        rpe = null,
        note = null,
        primarySource = ActivitySource.MANUAL,
        mergedSources = listOf(ActivitySource.MANUAL),
        hasStreams = false,
    )

    /** A [SuggestionInput] with a 7-day horizon, 40 AU/day of history and nothing else going on. */
    @Suppress("LongParameterList")
    fun input(
        horizonDays: Int = 7,
        goals: List<Goal> = emptyList(),
        events: List<EventOccurrence> = emptyList(),
        lockedPlanned: List<PlannedSession> = emptyList(),
        recentLoad: List<DailyLoad> = loadHistory(),
        recovery: RecoveryState? = null,
        recentActivities: List<ActivitySummary> = emptyList(),
        profile: Profile = profile(),
        planStartDay: Long? = null,
        cycleStatusByDay: Map<Long, CycleStatus> = emptyMap(),
    ): SuggestionInput = SuggestionInput(
        today = TODAY,
        horizonDays = horizonDays,
        profile = profile,
        goals = goals,
        events = events,
        lockedPlanned = lockedPlanned,
        recentLoad = recentLoad,
        recovery = recovery,
        recentActivities = recentActivities,
        planStartDay = planStartDay,
        cycleStatusByDay = cycleStatusByDay,
    )

    /** One logged period start, [offset] days from [TODAY] (P11.2 fixtures). */
    fun cycleEntry(offset: Long, periodDays: Int? = null): CycleEntry = CycleEntry(
        id = 1L,
        periodStartDay = day(offset),
        periodEndDay = periodDays?.let { day(offset) + it - 1 },
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    /**
     * The horizon's [CycleStatus] map, built by the **real** [CycleEngine] from a single logged
     * period start [offset] days from [TODAY] — so a fixture says "today is cycle day N" by
     * setting `offset = -(N - 1)`, and every phase boundary is the engine's, not the test's.
     *
     * One entry means no interval, so the forecast runs on the 28/5 defaults at
     * [com.myhealth.domain.model.CycleConfidence.LOW] — the case P11.2's "log your period to
     * improve this" wording is for.
     */
    fun cycleStatuses(offset: Long, horizonDays: Int = 7): Map<Long, CycleStatus> =
        CycleEngine.statusesFor(TODAY_DAY, TODAY_DAY + horizonDays, listOf(cycleEntry(offset)))

    /**
     * P14.5: a [MuscleLoadState] built by the **real** [MuscleLoadEngine] from the sessions given,
     * so a fixture says "a hard run yesterday" rather than hand-writing per-group AU.
     *
     * The default `ctl = 40.0` puts the reference at `max(0.35 × 40, 12) = 14.0`.
     */
    fun muscleLoad(
        ctl: Double = 40.0,
        sessions: List<MuscleSession> = emptyList(),
        todayDay: Long = TODAY_DAY,
    ): MuscleLoadState = MuscleLoadEngine.compute(
        MuscleLoadInput(today = todayDay, ctl = ctl, sessions = sessions),
    )

    fun muscleSession(
        day: Long,
        sportGroup: SportGroup = SportGroup.RUN,
        trimp: Double = 200.0,
    ): MuscleSession = MuscleSession(day = day, sportGroup = sportGroup, trimp = trimp)

    /** A seeded grid for constraint tests — the engine's step 1 without the engine. */
    fun grid(input: SuggestionInput): SuggestionGrid = SuggestionGrid.seed(input)

    fun candidate(sessionType: SessionType, day: Long, minutes: Int? = null): Candidate {
        val entry = requireNotNull(SessionCatalog.entryFor(sessionType)) { "no catalog row for $sessionType" }
        return Candidate(entry = entry, day = day, minutes = minutes ?: entry.defaultMin)
    }
}
