package com.myhealth.ui.bike

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.engine.bike.BikeDefaults
import com.myhealth.domain.engine.bike.FtpEstimator
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.RideBestRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/** How many `POWER_20MIN` rows the FTP estimate considers (mirrors `GoalsViewModel`, P12.2). */
private const val FTP_BEST_CANDIDATES = 50

/**
 * Backs [BikeScreen] (PLAN "UI.", More → "Bike & power", P12.4): the FTP card (resolved the same
 * way `GoalsViewModel` does, via [FtpEstimator]) and the power/time PR tables from `ride_best`.
 */
class BikeViewModel(
    private val rideBestRepo: RideBestRepository,
    private val profileRepo: ProfileRepository,
    private val activityRepo: ActivityRepository,
    private val clock: PlatformClock,
) : ViewModel() {

    private fun today(): LocalDate = clock.today()

    private data class FtpInputs(
        val twentyMinuteBests: List<RideBest>,
        val manualWatts: Int?,
        val indoorTrainerAvailable: Boolean,
    )

    private val ftpInputs = combine(
        rideBestRepo.observeByKind(RideBestKind.POWER_20MIN, FTP_BEST_CANDIDATES),
        profileRepo.observeProfile(),
    ) { twentyMinuteBests, profile ->
        FtpInputs(twentyMinuteBests, profile?.ftpWattsManual, profile?.indoorTrainerAvailable ?: false)
    }

    val state: StateFlow<BikeUiState> = combine(
        rideBestRepo.observeBestPerKind(),
        ftpInputs,
        activityRepo.observeRange(today().toEpochDays() - BikeDefaults.FTP_WINDOW_DAYS, today().toEpochDays()),
    ) { bests, inputs, rides ->
        val ftp = FtpEstimator.estimateFromSummaries(
            manualWatts = inputs.manualWatts,
            powerBests = inputs.twentyMinuteBests,
            rides = rides,
            todayDay = today().toEpochDays(),
        )
        BikeUiState(
            isLoading = false,
            ftp = ftp,
            powerBests = bests.powerBests(),
            timeBests = bests.timeBests(),
            indoorTrainerAvailable = inputs.indoorTrainerAvailable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BikeUiState())
}
