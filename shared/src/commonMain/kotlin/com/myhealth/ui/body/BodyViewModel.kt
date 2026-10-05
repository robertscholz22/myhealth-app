package com.myhealth.ui.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/**
 * Backs [BodyScreen] — latest weight + goal delta, "Log weight", the P8.3 charts (weight with a
 * 7-day average and the goal line, body fat, resting HR, sleep duration) and the history list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BodyViewModel(
    private val profileRepo: ProfileRepository,
    private val bodyRepo: BodyRepository,
    private val healthRepo: HealthRepository,
    private val syncScheduler: SyncScheduler,
    private val clock: PlatformClock,
) : ViewModel() {

    private val dialogOpen = MutableStateFlow(false)
    private val range = MutableStateFlow(BodyRange.D90)
    private fun today(): LocalDate = clock.today()

    private val windowed = range.flatMapLatest { selected ->
        val to = today().toEpochDays()
        combine(
            bodyRepo.observeRange(to - selected.days + 1, to),
            healthRepo.observeRange(to - selected.days + 1, to),
        ) { measurements, health -> selected to (measurements to health) }
    }

    private val sleep = healthRepo.observeSleepRange(
        today().toEpochDays() - SLEEP_BAR_NIGHTS + 1,
        today().toEpochDays(),
    )

    val state: StateFlow<BodyUiState> = combine(
        profileRepo.observeProfile(),
        windowed,
        sleep,
        dialogOpen,
    ) { profile, (selected, data), nights, showDialog ->
        val (measurements, health) = data
        BodyUiState(
            isLoading = false,
            goalWeightKg = profile?.goalWeightKg,
            range = selected,
            measurements = measurements.withinLastDays(selected.days, today()).sortedByRecencyDescending(),
            health = health,
            sleep = nights,
            today = today().toEpochDays(),
            showLogDialog = showDialog,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BodyUiState())

    fun setRange(newRange: BodyRange) {
        range.value = newRange
    }

    fun openLogDialog() {
        dialogOpen.value = true
    }

    fun dismissLogDialog() {
        dialogOpen.value = false
    }

    fun logWeight(weightKg: Double, bodyFatPercent: Double?) {
        viewModelScope.launch {
            bodyRepo.insert(
                BodyMeasurement(
                    measuredAtMillis = clock.millis(),
                    day = today().toEpochDays(),
                    weightKg = weightKg,
                    bodyFatPercent = bodyFatPercent,
                    muscleMassKg = null,
                    boneMassKg = null,
                    bodyWaterPercent = null,
                    source = ActivitySource.MANUAL,
                ),
            )
            dialogOpen.value = false
            // A new weight changes BMR, TDEE and every macro floor (P4.12).
            syncScheduler.requestTargetRecompute()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { bodyRepo.delete(id) }
    }
}
