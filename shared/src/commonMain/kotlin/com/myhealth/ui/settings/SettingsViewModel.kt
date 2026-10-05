package com.myhealth.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.domain.engine.bike.BikeDefaults
import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.bike.FtpEstimator
import com.myhealth.domain.model.AppSettings
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.Sex
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.ImportRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.RideBestRepository
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.sync.SyncScheduler
import com.myhealth.ui.common.UiMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/** How many `POWER_20MIN` rows the FTP hint considers (mirrors `GoalsViewModel`, P12.4). */
private const val FTP_HINT_CANDIDATES = 50

/** Every profile field plus every [AppSettings] key, edited in place (§4.2 Settings). */
class SettingsViewModel(
    private val profileRepo: ProfileRepository,
    private val settingsRepo: SettingsRepository,
    private val importRepo: ImportRepository,
    private val rideBestRepo: RideBestRepository,
    private val activityRepo: ActivityRepository,
    private val syncScheduler: SyncScheduler,
    private val clock: PlatformClock,
) : ViewModel() {

    private val orphanCleanupMessage = MutableStateFlow<UiMessage?>(null)

    /** One-shot result of [removeOrphanedImportData], resolved and shown by the screen's snackbar. */
    val message: StateFlow<UiMessage?> = orphanCleanupMessage.asStateFlow()

    private fun today(): LocalDate = clock.today()

    /** The FTP estimate ignoring any manual override (P12.4 "UI." — the override field's hint). */
    private val ftpEstimate: Flow<FtpEstimate?> = combine(
        rideBestRepo.observeByKind(RideBestKind.POWER_20MIN, FTP_HINT_CANDIDATES),
        activityRepo.observeRange(today().toEpochDays() - BikeDefaults.FTP_WINDOW_DAYS, today().toEpochDays()),
    ) { twentyMinuteBests, rides ->
        FtpEstimator.estimateFromSummaries(
            manualWatts = null,
            powerBests = twentyMinuteBests,
            rides = rides,
            todayDay = today().toEpochDays(),
        )
    }

    val state: StateFlow<SettingsUiState> = combine(
        profileRepo.observeProfile(),
        settingsRepo.settings,
        ftpEstimate,
    ) { profile, settings, ftp ->
        SettingsUiState(isLoading = false, profile = profile, settings = settings, ftpEstimate = ftp)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onProfileChange(profile: Profile) {
        viewModelScope.launch {
            val previous = state.value.profile
            profileRepo.upsert(profile.copy(updatedAtMillis = clock.millis()))
            // P11.1: switching the profile to FEMALE turns the cycle tracker on once. It is not
            // turned back off here — a user who switched it off in Settings keeps it off.
            if (profile.sex == Sex.FEMALE && previous?.sex != Sex.FEMALE) {
                settingsRepo.setCycleTrackingEnabled(true)
            }
            // Sex, height, NEAT, goal weight and pace all feed the target engine (P4.12).
            syncScheduler.requestTargetRecompute()
        }
    }

    fun onSettingsChange(settings: AppSettings) {
        viewModelScope.launch { settingsRepo.update { settings } }
    }

    /**
     * BUG-12b (hotfix 1.0.3): removes file-import source records that were never stamped with an
     * `import_record` (imported before DB v4) and so "Undo import" can no longer reach. The count
     * comes back as the snackbar message.
     */
    fun removeOrphanedImportData() {
        viewModelScope.launch {
            orphanCleanupMessage.value = when (val outcome = importRepo.removeOrphanedImportData()) {
                is Outcome.Ok -> UiMessage.of(
                    Res.string.settings_orphan_cleanup_result_format,
                    outcome.value.activitiesDeleted,
                    outcome.value.activitiesKept,
                )
                is Outcome.Err -> UiMessage.of(Res.string.settings_orphan_cleanup_failed)
            }
        }
    }

    /**
     * BUG-14: schedules a load/recovery recompute over the **whole** history (`fromDay = 0` is
     * clamped to the first activity by `LoadRecomputeService`) — the repair for activities that
     * lost their TRIMP when an earlier historical recompute was cancelled.
     */
    fun recomputeTrainingLoad() {
        syncScheduler.requestLoadRecompute(0L)
        orphanCleanupMessage.value = UiMessage.of(Res.string.settings_recompute_load_scheduled)
    }

    fun consumeMessage() {
        orphanCleanupMessage.value = null
    }
}
