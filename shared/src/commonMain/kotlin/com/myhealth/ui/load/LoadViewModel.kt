package com.myhealth.ui.load

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.minuteOfDay
import com.myhealth.data.time.timeZone
import com.myhealth.data.time.today
import com.myhealth.domain.engine.load.RecoveryEngine
import com.myhealth.domain.engine.load.RecoveryInput
import com.myhealth.domain.engine.strength.MuscleLoadEngine
import com.myhealth.domain.engine.strength.MuscleLoadInput
import com.myhealth.domain.engine.strength.MuscleLoadState
import com.myhealth.domain.engine.strength.MuscleSession
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.DailyHealthSummary
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.RecoveryState
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.LoadRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.StrengthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import com.myhealth.data.time.PlatformClock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

private const val RECOVERY_LOOKBACK_DAYS = 30L
private const val BEDTIME_HISTORY_DAYS = 14L
private const val SLEEP_DEBT_WINDOW_DAYS = 7L
private const val HRV_WINDOW_DAYS = 7L

/** The four inputs [LoadViewModel] combines into one [LoadUiState]. */
private data class LoadCore(
    val series: List<DailyLoad>,
    val latest: DailyLoad?,
    val recovery: RecoveryState?,
    val muscleLoad: MuscleLoadState,
)

/**
 * Backs [LoadScreen] (PLAN §4.2 Load & recovery, P5.6/P14.8): the range-selected daily-load series,
 * the latest cached row for the ATL/CTL/ACWR/TSB tiles, a live [RecoveryState] recomputed from the
 * same inputs [com.myhealth.data.repository.LoadRecomputeService] uses — `daily_load` only caches
 * the score/band/confidence/flags totals, not the per-component breakdown (§2.2.6), so the
 * component list (name, points/max) is rebuilt here rather than read from the cache — and today's
 * [MuscleLoadState] for the "Muscle load" card, resolved the way
 * [com.myhealth.data.repository.SuggestionMuscleResolver] assembles it for the suggester (P14.5),
 * but re-derived directly against the domain repositories since `ui/` may not import `data/`
 * (`ArchitectureTest`) — the same seam [com.myhealth.ui.zones.ZonesViewModel] uses for pace bands
 * (P14.6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadViewModel(
    private val loadRepo: LoadRepository,
    private val healthRepo: HealthRepository,
    private val profileRepo: ProfileRepository,
    private val activityRepo: ActivityRepository,
    private val planRepo: PlanRepository,
    private val strengthRepo: StrengthRepository,
    private val clock: PlatformClock,
) : ViewModel() {

    private val range = MutableStateFlow(LoadRange.D28)

    private fun today(): Long = clock.today().toEpochDays()

    private val series = range.flatMapLatest { r ->
        loadRepo.observeRange(today() - r.days + 1, today())
    }

    private val recovery = combine(
        loadRepo.observeLatest(),
        profileRepo.observeProfile(),
        healthRepo.observeRange(today() - RECOVERY_LOOKBACK_DAYS, today()),
        healthRepo.observeSleepRange(today() - BEDTIME_HISTORY_DAYS, today()),
    ) { latest, profile, health, sleep ->
        recoveryStateFor(latest, profile, health, sleep, today(), clock.timeZone)
    }

    private val muscleLoad = combine(
        activityRepo.observeRange(today() - MuscleLoadEngine.WINDOW_DAYS, today()),
        loadRepo.observeLatest(),
    ) { activities, latest -> activities to (latest?.ctl ?: 0.0) }
        .flatMapLatest { (activities, ctl) ->
            flow { emit(resolveMuscleLoadState(activities, ctl, today(), planRepo, strengthRepo)) }
        }

    private val core = combine(series, loadRepo.observeLatest(), recovery, muscleLoad) { s, latest, r, m ->
        LoadCore(series = s, latest = latest, recovery = r, muscleLoad = m)
    }

    val state: StateFlow<LoadUiState> = combine(range, core) { r, c ->
        LoadUiState(
            isLoading = false,
            range = r,
            series = c.series,
            latest = c.latest,
            recovery = c.recovery,
            muscleLoad = c.muscleLoad,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadUiState())

    fun setRange(newRange: LoadRange) {
        range.value = newRange
    }
}

/**
 * Assembles a [MuscleLoadState] from [activities] over the last [MuscleLoadEngine.WINDOW_DAYS]
 * days plus [ctl] — the same join [com.myhealth.data.repository.SuggestionMuscleResolver] performs
 * (activity → the planned session that claimed it via `linkedActivityId`, for its `sessionType`,
 * and, through `plannedSession.workoutId`, its actual exercise list), shared by [LoadViewModel] and
 * `TodayViewModel` so both cards agree.
 */
internal suspend fun resolveMuscleLoadState(
    activities: List<ActivitySummary>,
    ctl: Double,
    todayDay: Long,
    planRepo: PlanRepository,
    strengthRepo: StrengthRepository,
): MuscleLoadState {
    val fromDay = todayDay - MuscleLoadEngine.WINDOW_DAYS
    val planned = planRepo.getSessions(fromDay, todayDay)
    val linked = planned.filter { it.linkedActivityId != null }.associateBy { it.linkedActivityId!! }
    val workouts = mutableMapOf<Long, StrengthWorkout?>()
    val sessions = activities.map { activity ->
        val link = linked[activity.id]
        MuscleSession(
            day = activity.day,
            sportGroup = activity.sportGroup,
            sessionType = link?.sessionType,
            trimp = activity.trimp ?: 0.0,
            workout = link?.workoutId?.let { id -> workouts.getOrPut(id) { strengthRepo.getById(id) } },
        )
    }
    return MuscleLoadEngine.compute(MuscleLoadInput(today = todayDay, ctl = ctl, sessions = sessions))
}

/** Rebuilds a [RecoveryInput] for `today` from bulk-fetched history, mirroring
 * `LoadRecomputeService.recoveryInputFor` but scoped to a single day for live UI display. */
private fun recoveryStateFor(
    latest: DailyLoad?,
    profile: Profile?,
    health: List<DailyHealthSummary>,
    sleep: List<SleepRecord>,
    today: Long,
    zone: TimeZone,
): RecoveryState? {
    if (profile == null) return null
    val day = latest?.day ?: today
    val healthByDay = health.associateBy { it.day }
    val sleepByNight = sleep.associateBy { it.night }
    val lastNight = sleepByNight[day]

    val input = RecoveryInput(
        day = day,
        lastNight = lastNight,
        bedtimeMinuteOfDay = lastNight?.let { minuteOfDay(it.startAtMillis, zone) },
        bedtimeMinutesLast14 = ((day - BEDTIME_HISTORY_DAYS) until day).mapNotNull { night ->
            sleepByNight[night]?.let { minuteOfDay(it.startAtMillis, zone) }
        },
        sleepMinutesLast7 = (day - SLEEP_DEBT_WINDOW_DAYS + 1..day).mapNotNull { sleepByNight[it]?.totalSleepMin },
        sleepTargetHours = profile.sleepTargetHours,
        restingHrToday = healthByDay[day]?.restingHr ?: healthByDay[day - 1]?.restingHr,
        restingHrLast30 = (day - RECOVERY_LOOKBACK_DAYS until day).mapNotNull { healthByDay[it]?.restingHr },
        load = latest,
        hrvTodayMs = healthByDay[day]?.hrvRmssdMs,
        hrvLast7Ms = (day - HRV_WINDOW_DAYS until day).mapNotNull { healthByDay[it]?.hrvRmssdMs },
    )
    return RecoveryEngine.compute(input)
}

private fun minuteOfDay(atMillis: Long, zone: TimeZone): Int =
    minuteOfDay(atMillis, zone)
