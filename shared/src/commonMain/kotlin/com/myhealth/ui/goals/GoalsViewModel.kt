package com.myhealth.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.engine.bike.FtpEstimator
import com.myhealth.domain.engine.goal.GoalProgress
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.repository.GoalRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.RideBestRepository
import com.myhealth.domain.repository.RunningBestRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.decodePreferredSports
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/**
 * The windows the progress engine needs: a year of weights, and 90 days of sessions — four weeks
 * for the `CONSISTENCY` and `BIKE_VOLUME` averages, the full `FtpEstimator` look-back for the
 * session-NP rung of the FTP estimate (P12.2). Both averages filter their own four weeks out again.
 */
private const val ACTIVITY_WINDOW_DAYS = 90L
private const val WEIGHT_WINDOW_DAYS = 365L

/**
 * How many `POWER_20MIN` rows the FTP estimate considers. The repository hands them out best
 * first, and the estimator then drops everything older than 90 days, so this only has to be deep
 * enough that a recent effort is still in the list behind older, stronger ones.
 */
private const val FTP_BEST_CANDIDATES = 50

/**
 * Backs [GoalsScreen] (PLAN §4.2 "Goals", P6.1): the goal list with the progress
 * [com.myhealth.domain.engine.goal.GoalProgress] computes for each one, plus the status actions.
 */
class GoalsViewModel(
    private val goalRepo: GoalRepository,
    private val runningBestRepo: RunningBestRepository,
    private val bodyRepo: BodyRepository,
    private val activityRepo: ActivityRepository,
    private val rideBestRepo: RideBestRepository,
    private val profileRepo: ProfileRepository,
    private val clock: PlatformClock,
) : ViewModel() {

    private val message = MutableStateFlow<UiMessage?>(null)

    private fun today(): LocalDate = clock.today()

    /**
     * The cycling goals' inputs (P12.2): the ride PR table, the FTP candidates, the override, and
     * (P12.4) the `CYCLE` preferred-sports cap that gates [GoalsUiState.showCycleCapHint].
     */
    private data class BikeInputs(
        val prPerKind: List<RideBest>,
        val twentyMinuteBests: List<RideBest>,
        val manualFtpWatts: Int?,
        val cycleCapPerWeek: Int,
    )

    private val bikeInputs = combine(
        rideBestRepo.observeBestPerKind(),
        rideBestRepo.observeByKind(RideBestKind.POWER_20MIN, FTP_BEST_CANDIDATES),
        profileRepo.observeProfile(),
    ) { prPerKind, twentyMinute, profile ->
        BikeInputs(
            prPerKind = prPerKind,
            twentyMinuteBests = twentyMinute,
            manualFtpWatts = profile?.ftpWattsManual,
            cycleCapPerWeek = decodePreferredSports(profile?.preferredSportsJson.orEmpty())[SportGroup.CYCLE] ?: 0,
        )
    }

    val state: StateFlow<GoalsUiState> = combine(
        goalRepo.observeAll(),
        // P19.1: the PR table (for the all-time note) plus every effort of the recent-form window.
        combine(
            runningBestRepo.observeBestPerDistance(),
            runningBestRepo.observeSince(today().toEpochDays() - GoalProgress.RECENT_FORM_DAYS),
        ) { prs, recent -> (prs + recent).distinctBy { it.id } },
        bodyRepo.observeRange(today().toEpochDays() - WEIGHT_WINDOW_DAYS, today().toEpochDays()),
        activityRepo.observeRange(today().toEpochDays() - ACTIVITY_WINDOW_DAYS, today().toEpochDays()),
        combine(bikeInputs, message) { bike, msg -> bike to msg },
    ) { goals, bests, weights, activities, (bike, msg) ->
        val ftp = FtpEstimator.estimateFromSummaries(
            manualWatts = bike.manualFtpWatts,
            powerBests = bike.twentyMinuteBests,
            rides = activities,
            todayDay = today().toEpochDays(),
        )
        val rows = goalRows(goals, bests, weights, activities, today(), bike.prPerKind, ftp)
        val active = rows.activeOnly().sortedForDisplay()
        GoalsUiState(
            isLoading = false,
            active = active,
            archived = rows.archivedOnly().sortedForDisplay(),
            message = msg,
            showCycleCapHint = bike.cycleCapPerWeek <= 0 && active.any { it.goal.type in BIKE_GOAL_TYPES },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsUiState())

    fun makePrimary(id: Long) = run(id, UiMessage.of(Res.string.goal_message_primary_updated)) { goalRepo.setPrimary(id) }

    fun markAchieved(id: Long) =
        run(id, UiMessage.of(Res.string.goal_message_achieved)) { goalRepo.setStatus(id, GoalStatus.ACHIEVED) }

    fun markAbandoned(id: Long) =
        run(id, UiMessage.of(Res.string.goal_message_abandoned)) { goalRepo.setStatus(id, GoalStatus.ABANDONED) }

    fun reactivate(id: Long) =
        run(id, UiMessage.of(Res.string.goal_message_reactivated)) { goalRepo.setStatus(id, GoalStatus.ACTIVE) }

    fun delete(id: Long) = run(id, UiMessage.of(Res.string.goal_message_deleted)) { goalRepo.delete(id) }

    fun consumeMessage() {
        message.value = null
    }

    private fun run(id: Long, success: UiMessage, block: suspend () -> Outcome<*>) {
        viewModelScope.launch {
            message.value = when (block()) {
                is Outcome.Ok -> success
                is Outcome.Err -> UiMessage.of(Res.string.goal_message_update_failed, id)
            }
        }
    }
}
