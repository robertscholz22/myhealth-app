package com.myhealth.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.today
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.ui.body.SLEEP_BAR_NIGHTS
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Backs [SleepScreen]: the last [SLEEP_BAR_NIGHTS] nights and the profile's sleep target. */
class SleepViewModel(
    profileRepo: ProfileRepository,
    healthRepo: HealthRepository,
    clock: PlatformClock,
) : ViewModel() {

    private val today: Long = clock.today().toEpochDays()

    val state: StateFlow<SleepUiState> = combine(
        profileRepo.observeProfile(),
        healthRepo.observeSleepRange(today - SLEEP_BAR_NIGHTS + 1, today),
    ) { profile, nights ->
        SleepUiState(
            isLoading = false,
            today = today,
            nights = nights,
            targetHours = profile?.sleepTargetHours ?: SleepUiState().targetHours,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepUiState(today = today))
}
