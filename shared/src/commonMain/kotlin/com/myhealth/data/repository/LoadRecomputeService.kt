package com.myhealth.data.repository

import com.myhealth.data.db.dao.ActivityDao
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.minuteOfDay
import com.myhealth.data.time.timeZone
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.engine.bike.BikeBestEngine
import com.myhealth.domain.engine.bike.BikeDefaults
import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.bike.FtpEstimator
import com.myhealth.domain.engine.load.DayLoadDetail
import com.myhealth.domain.engine.load.DayLoadInput
import com.myhealth.domain.engine.load.HrBounds
import com.myhealth.domain.engine.load.LoadSeriesEngine
import com.myhealth.domain.engine.load.RecoveryEngine
import com.myhealth.domain.engine.load.RecoveryInput
import com.myhealth.domain.engine.load.TrimpCalculator
import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.engine.plan.PlannedAutoCompleter
import com.myhealth.domain.engine.running.RunningBestEngine
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.DailyHealthSummary
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.LoadRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.RideBestRepository
import com.myhealth.domain.repository.RunningBestRepository
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.epochDayDate
import com.myhealth.domain.util.runCatchingApp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/**
 * Recomputes `activity_session.trimp`, `daily_load`, `running_best` and `ride_best` after new or
 * changed activity/health data (PLAN P5.5, extended by P12.2).
 *
 * Pipeline for [recompute]`(fromDay)`:
 * 1. Resolve [HrBounds] once for the whole run: resting HR from the last 7 days
 *    ([TrimpDefaults.REST_HR_WINDOW_DAYS]), observed max HR from the last 365 days
 *    ([TrimpDefaults.OBSERVED_MAX_HR_WINDOW_DAYS]), both from [HealthRepository] / [ActivityRepository].
 * 2. Refresh `ride_best` for every `CYCLE` activity in the window via [BikeBestEngine.computeRows] +
 *    [RideBestRepository.replaceForActivity] (idempotent, delete-then-insert), then resolve the FTP
 *    **once** for the whole run with [FtpEstimator] off the refreshed bests of the last 90 days plus
 *    the rides themselves (P12.2). The order matters: a ride imported minutes ago must be able to
 *    raise today's FTP before that FTP is used to score it.
 * 3. For every activity in `[fromDay − 28, today]` — the EWMA prefix `LoadSeriesEngine` needs —
 *    run [TrimpCalculator.computeForSession] with that FTP and persist via
 *    [ActivityRepository.setTrimp] only when the value or method actually changed (this is what
 *    makes a rerun idempotent and cheap); refresh `running_best` for every `RUN` activity via
 *    [RunningBestEngine.computeRows] + [RunningBestRepository.replaceForActivity].
 * 4. Rebuild the day series from [ActivityDao.sumTrimpPerDay] (the just-written ground truth) via
 *    [LoadSeriesEngine.computeDetailed], then layer [RecoveryEngine] on top per day using sleep,
 *    resting HR and HRV history, and write the result via [LoadRepository.upsertAll].
 *
 * BUG-18: the EWMAs always run from the first activity day, never from `fromDay − 28`. Seeding them
 * at 0 at the window start under-reported today's CTL (a 28-day EWMA started at 0 reaches only
 * ~87 % of its value) and froze every day that left the window at a zero-seeded value — the
 * owner's CTL read 0 at the end of August 2026 after a 340 AU week. Only the window is written
 * (plus recovery), but stored rows before it are compared with the full series and rewritten from
 * the first one that disagrees, which repairs old databases and restored backups without a flag.
 *
 * A missing profile (onboarding not finished) is a no-op, not an error — the profile write itself
 * triggers a recompute request (mirrors [com.myhealth.data.repository.RoomNutritionRepository]).
 */
class LoadRecomputeService(
    private val activityDao: ActivityDao,
    private val activityRepo: ActivityRepository,
    private val loadRepo: LoadRepository,
    private val runningBestRepo: RunningBestRepository,
    private val rideBestRepo: RideBestRepository,
    private val profileRepo: ProfileRepository,
    private val healthRepo: HealthRepository,
    private val settingsRepo: SettingsRepository,
    private val clock: PlatformClock,
    /** P6.8: `null` disables planned-session auto-completion (the load tests do not need it). */
    private val planRepo: PlanRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun recompute(fromDay: Long): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { recomputeInternal(fromDay) }
    }

    private suspend fun recomputeInternal(fromDay: Long) {
        val profile = profileRepo.getProfile() ?: return
        val today = clock.todayEpochDay()

        // POLISH-13: the series (and the cached `daily_load` rows) must never start before the
        // first activity. No activities at all means no series and no cached rows.
        val firstActivityDay = activityDao.getFirstActivityDay()
        if (firstActivityDay == null) {
            loadRepo.deleteBefore(Long.MAX_VALUE)
            return
        }

        val rawWindowStart = minOf(fromDay, today) - TrimpDefaults.CHRONIC_WINDOW_DAYS
        val windowStart = maxOf(rawWindowStart, firstActivityDay)
        val maxHrWindowStart = today - TrimpDefaults.OBSERVED_MAX_HR_WINDOW_DAYS + 1
        val unionStart = minOf(windowStart, maxHrWindowStart)

        val allSessions = activityRepo.getRange(unionStart, today)
        val restingHr7d = healthRepo.observeRange(today - TrimpDefaults.REST_HR_WINDOW_DAYS + 1, today)
            .first().mapNotNull { it.restingHr }
        val observedMaxHr = allSessions
            .filter { it.day >= maxHrWindowStart }
            .mapNotNull { it.maxHr }
            .maxOrNull()
        val bounds = HrBounds.compute(profile, today.epochDayDate(), restingHr7d, observedMaxHr)

        val includeTreadmill = settingsRepo.settings.first().includeTreadmillInPrs
        val sessions = allSessions.filter { it.day in windowStart..today }

        // P12.2: ride bests first, then the FTP they feed, then the TRIMP that FTP scores.
        refreshRideBests(sessions)
        val ftp = resolveFtp(profile, allSessions, today)

        for (session in sessions) {
            recomputeOne(session, profile, bounds, includeTreadmill, ftp)
        }

        val trimpByDay = activityDao.sumTrimpPerDay(firstActivityDay, today).first().associate { it.day to it.trimp }
        val countByDay = activityDao.countPerDay(firstActivityDay, today).associate { it.day to it.count }
        val dayInputs = (firstActivityDay..today).map { day ->
            DayLoadInput(day = day, trimp = trimpByDay[day] ?: 0.0, sessionCount = countByDay[day] ?: 0)
        }
        val allDetails = LoadSeriesEngine.computeDetailed(dayInputs, clock.millis())
        val writeStart = firstStaleDay(allDetails, windowStart) ?: windowStart
        val details = allDetails.filter { it.load.day >= writeStart }

        val zone = clock.timeZone
        val healthByDay = healthRepo.observeRange(writeStart - RECOVERY_LOOKBACK_DAYS, today)
            .first().associateBy { it.day }
        val sleepByNight = healthRepo.observeSleepRange(writeStart - BEDTIME_HISTORY_DAYS, today)
            .first().associateBy { it.night }

        val rows = details.map { detail ->
            withRecovery(detail, profile, healthByDay, sleepByNight, zone)
        }
        loadRepo.upsertAll(rows)
        loadRepo.deleteBefore(firstActivityDay)
        autoCompletePlanned(today)
    }

    /**
     * BUG-18: the first day before [windowStart] whose stored row disagrees with the full series
     * (missing, or a different TRIMP, session count, ATL or CTL), or `null` when all of them agree.
     */
    private suspend fun firstStaleDay(details: List<DayLoadDetail>, windowStart: Long): Long? {
        val before = details.filter { it.load.day < windowStart }
        if (before.isEmpty()) return null
        val stored = loadRepo.getRange(before.first().load.day, windowStart - 1).associateBy { it.day }
        return before.firstOrNull { detail ->
            val row = stored[detail.load.day]
            row == null || !row.sameSeriesAs(detail.load)
        }?.load?.day
    }

    private fun DailyLoad.sameSeriesAs(other: DailyLoad): Boolean =
        sessionCount == other.sessionCount &&
            abs(trimp - other.trimp) < SERIES_EPSILON &&
            abs(atl - other.atl) < SERIES_EPSILON &&
            abs(ctl - other.ctl) < SERIES_EPSILON

    /**
     * P6.8's completion linking: an activity that matches a still-`PLANNED` session's sport on the
     * same day with confidence ≥ [PlannedAutoCompleter.MIN_CONFIDENCE] marks it `COMPLETED` and
     * records the `linkedActivityId`. Run over `[today − 7, today]` after every ingest, which is
     * idempotent — a session that is already completed or linked is skipped by the engine itself.
     */
    private suspend fun autoCompletePlanned(today: Long) {
        val plans = planRepo ?: return
        val fromDay = today - AUTO_COMPLETE_WINDOW_DAYS
        val sessions = plans.getSessions(fromDay, today).filter { it.status == PlannedStatus.PLANNED }
        if (sessions.isEmpty()) return
        val activities = activityRepo.observeRange(fromDay, today).first()
        PlannedAutoCompleter.complete(sessions, activities, clock.timeZone).forEach { completion ->
            plans.linkActivity(completion.sessionId, completion.activityId)
            plans.setSessionStatus(completion.sessionId, PlannedStatus.COMPLETED)
        }
    }

    /** Every `CYCLE` session in the window gets its `ride_best` rows rebuilt (P12.2). */
    private suspend fun refreshRideBests(sessions: List<ActivitySession>) {
        for (session in sessions) {
            if (session.sportGroup != SportGroup.CYCLE) continue
            // `createdAtMillis` for the same reason as the running bests below.
            val rows = BikeBestEngine.computeRows(session, session.createdAtMillis)
            rideBestRepo.replaceForActivity(session.id, rows)
        }
    }

    /**
     * The FTP for this run (P12.2) — resolved **once**, never cached: the manual override, else the
     * refreshed `ride_best` rows of the last 90 days, else the rides of that window themselves.
     */
    private suspend fun resolveFtp(
        profile: Profile,
        allSessions: List<ActivitySession>,
        today: Long,
    ): FtpEstimate? {
        val since = today - BikeDefaults.FTP_WINDOW_DAYS
        val bests = rideBestRepo.getSince(since)
        val rides = allSessions.filter { it.sportGroup == SportGroup.CYCLE && it.day >= since }
        return FtpEstimator.estimateFromSessions(profile.ftpWattsManual, bests, rides, today)
    }

    private suspend fun recomputeOne(
        session: ActivitySession,
        profile: Profile,
        bounds: HrBounds,
        includeTreadmill: Boolean,
        ftp: FtpEstimate?,
    ) {
        val result = TrimpCalculator.computeForSession(session, profile.sex, bounds, ftp)
        if (session.trimp != result.trimp || session.loadMethod != result.method) {
            activityRepo.setTrimp(session.id, result.trimp, result.method)
        }
        if (session.sportGroup == SportGroup.RUN) {
            // `createdAtMillis`, not `updatedAtMillis`: this pipeline itself bumps the latter on
            // every trimp write, which would otherwise make the running-best rows churn on every
            // rerun even though nothing about the effort changed.
            val rows = RunningBestEngine.computeRows(session, includeTreadmill, session.createdAtMillis)
            runningBestRepo.replaceForActivity(session.id, rows)
        }
    }

    private fun withRecovery(
        detail: DayLoadDetail,
        profile: Profile,
        healthByDay: Map<Long, DailyHealthSummary>,
        sleepByNight: Map<Long, SleepRecord>,
        zone: TimeZone,
    ): DailyLoad {
        val day = detail.load.day
        val recovery = RecoveryEngine.compute(
            recoveryInputFor(day, detail.load, profile, healthByDay, sleepByNight, zone),
        )
        return detail.load.copy(
            recoveryScore = recovery.score,
            recoveryBand = recovery.band,
            recoveryConfidence = recovery.confidence,
            flags = recovery.flags,
        )
    }

    private fun recoveryInputFor(
        day: Long,
        load: DailyLoad,
        profile: Profile,
        healthByDay: Map<Long, DailyHealthSummary>,
        sleepByNight: Map<Long, SleepRecord>,
        zone: TimeZone,
    ): RecoveryInput {
        val lastNight = sleepByNight[day]
        val restingHrToday = healthByDay[day]?.restingHr ?: healthByDay[day - 1]?.restingHr
        return RecoveryInput(
            day = day,
            lastNight = lastNight,
            bedtimeMinuteOfDay = lastNight?.let { minuteOfDay(it.startAtMillis, zone) },
            bedtimeMinutesLast14 = ((day - BEDTIME_HISTORY_DAYS) until day).mapNotNull { night ->
                sleepByNight[night]?.let { minuteOfDay(it.startAtMillis, zone) }
            },
            sleepMinutesLast7 = (day - SLEEP_DEBT_WINDOW_DAYS + 1..day).mapNotNull { sleepByNight[it]?.totalSleepMin },
            sleepTargetHours = profile.sleepTargetHours,
            restingHrToday = restingHrToday,
            restingHrLast30 = (day - RECOVERY_LOOKBACK_DAYS until day).mapNotNull { healthByDay[it]?.restingHr },
            load = load,
            hrvTodayMs = healthByDay[day]?.hrvRmssdMs,
            hrvLast7Ms = (day - HRV_WINDOW_DAYS until day).mapNotNull { healthByDay[it]?.hrvRmssdMs },
        )
    }


    private companion object {
        /** Resting-HR/HRV baseline lookback used by [RecoveryEngine] (§3.3). */
        const val RECOVERY_LOOKBACK_DAYS = 30L
        const val BEDTIME_HISTORY_DAYS = 14L
        const val SLEEP_DEBT_WINDOW_DAYS = 7L
        const val HRV_WINDOW_DAYS = 7L

        /** BUG-18: stored and recomputed EWMAs closer than this count as the same series. */
        const val SERIES_EPSILON = 1e-6

        /** P6.8 auto-completion looks one week back — a sync can deliver late-arriving sessions. */
        const val AUTO_COMPLETE_WINDOW_DAYS = 7L
    }
}
