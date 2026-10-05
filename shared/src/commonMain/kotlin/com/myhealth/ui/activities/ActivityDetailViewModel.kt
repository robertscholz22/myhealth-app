package com.myhealth.ui.activities

import com.myhealth.data.time.today
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.engine.activity.ActivityFields
import com.myhealth.domain.engine.bike.BikeDefaults
import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.bike.FtpEstimator
import com.myhealth.domain.engine.load.HrBounds
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.model.HrZoneScheme
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.RideBestRepository
import com.myhealth.sync.SyncScheduler
import com.myhealth.ui.zones.targetZoneIndexFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/** Fallback resting/max HR (§3.2.1) when the profile has neither a manual value nor an estimate. */
private const val FALLBACK_HR_REST = 60
private const val FALLBACK_HR_MAX = 190

/** How many `POWER_20MIN` rows the FTP resolution considers (mirrors `GoalsViewModel`, P12.4). */
private const val FTP_BEST_CANDIDATES = 50

private data class ActivityCore(
    val activity: ActivitySession?,
    val profile: Profile?,
    val showDeleteConfirm: Boolean,
    val deleted: Boolean,
)

private data class ActivityLinking(
    val day: CalendarDay?,
    val showEventPicker: Boolean,
)

/**
 * Backs [ActivityDetailScreen] (PLAN §4.2 Activity detail, P2.9): header stats, HR zone table,
 * title/note edits (pinning the edited field in `userEditedFields` so a later sync merge cannot
 * clobber it, §2.4), laps, delete-with-confirmation, and (P3.7) the "linked event" card: the
 * activity's day is observed through [CalendarRepository.observeDay] to find the occurrence, if
 * any, whose `linkedActivityId` is this activity, and to offer the day's other events as the
 * manual "Link to event…" picker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityDetailViewModel(
    private val activityId: Long,
    private val activityRepo: ActivityRepository,
    private val profileRepo: ProfileRepository,
    private val calendarRepo: CalendarRepository,
    private val rideBestRepo: RideBestRepository,
    private val syncScheduler: SyncScheduler,
    private val clock: PlatformClock,
) : ViewModel() {

    private val showDeleteConfirm = MutableStateFlow(false)
    private val deleted = MutableStateFlow(false)
    private val showEventPicker = MutableStateFlow(false)

    private val activityFlow: Flow<ActivitySession?> = activityRepo.observeFullById(activityId)

    private val dayCalendar: Flow<CalendarDay?> = activityFlow.flatMapLatest { activity ->
        if (activity == null) flowOf(null) else calendarRepo.observeDay(activity.day)
    }

    private val core = combine(
        activityFlow,
        profileRepo.observeProfile(),
        showDeleteConfirm,
        deleted,
    ) { activity, profile, showDelete, isDeleted -> ActivityCore(activity, profile, showDelete, isDeleted) }

    private val linking = combine(dayCalendar, showEventPicker) { day, picker -> ActivityLinking(day, picker) }

    /** The power card's FTP (P12.4): resolved the same way `GoalsViewModel` does, off the same
     * 90-day window `FtpEstimator`/`BikeDefaults.FTP_WINDOW_DAYS` reads. */
    private val ftp: Flow<FtpEstimate?> = combine(
        rideBestRepo.observeByKind(RideBestKind.POWER_20MIN, FTP_BEST_CANDIDATES),
        activityRepo.observeRange(today().toEpochDays() - BikeDefaults.FTP_WINDOW_DAYS, today().toEpochDays()),
        profileRepo.observeProfile(),
    ) { twentyMinuteBests, rides, profile ->
        FtpEstimator.estimateFromSummaries(
            manualWatts = profile?.ftpWattsManual,
            powerBests = twentyMinuteBests,
            rides = rides,
            todayDay = today().toEpochDays(),
        )
    }

    private fun today(): LocalDate = clock.today()

    val state: StateFlow<ActivityDetailUiState> = combine(core, linking, ftp) { c, l, ftpEstimate ->
        val model = hrZoneModelFor(c.profile, clock.today())
        val linkedSession = l.day?.planned?.firstOrNull { it.linkedActivityId == activityId }
        ActivityDetailUiState(
            isLoading = false,
            activity = c.activity,
            hrZoneMinutes = c.activity?.streams?.let(model::minutesPerZone).orEmpty(),
            hrZoneModel = model,
            targetZoneIndex = linkedSession?.sessionType?.let(::targetZoneIndexFor),
            showDeleteConfirm = c.showDeleteConfirm,
            deleted = c.deleted,
            linkedEvent = l.day?.events?.firstOrNull { it.linkedActivityId == activityId },
            dayEvents = l.day?.events.orEmpty(),
            showEventPicker = l.showEventPicker,
            ftp = ftpEstimate,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityDetailUiState())

    /** Saves a manually edited title, pinning [ActivityFields.TITLE] so a merge never overwrites it. */
    fun saveTitle(title: String) {
        val session = state.value.activity ?: return
        val trimmed = title.trim().ifBlank { null }
        if (trimmed == session.title) return
        viewModelScope.launch {
            activityRepo.upsert(
                session.copy(
                    title = trimmed,
                    userEditedFields = (session.userEditedFields + ActivityFields.TITLE).distinct(),
                    updatedAtMillis = clock.millis(),
                ),
            )
        }
    }

    fun saveNote(note: String) {
        viewModelScope.launch { activityRepo.setNote(activityId, note.trim().ifBlank { null }) }
    }

    /** 1–10 RPE entry (§4.2 Activity detail, P5.9): pins `rpe` in `userEditedFields` (via
     * [ActivityRepository.setRpe]) and requests a load recompute from this activity's day, since
     * the RPE ladder can change its TRIMP method and value (§3.2.2). */
    fun saveRpe(rpe: Int) {
        val activity = state.value.activity ?: return
        if (activity.rpe == rpe) return
        viewModelScope.launch {
            activityRepo.setRpe(activityId, rpe)
            syncScheduler.requestLoadRecompute(activity.day)
        }
    }

    fun requestDelete() {
        showDeleteConfirm.value = true
    }

    fun cancelDelete() {
        showDeleteConfirm.value = false
    }

    fun confirmDelete() {
        viewModelScope.launch {
            activityRepo.delete(activityId)
            showDeleteConfirm.value = false
            deleted.value = true
        }
    }

    fun openEventPicker() {
        showEventPicker.value = true
    }

    fun closeEventPicker() {
        showEventPicker.value = false
    }

    /** Manually linking to an event from the "Link to event…" picker (P3.7): the repository
     * upgrades the activity's sport to `SOCCER_MATCH` when the event is one. */
    fun linkEvent(eventId: Long) {
        viewModelScope.launch {
            calendarRepo.linkActivity(eventId, activityId, LinkMethod.MANUAL)
            showEventPicker.value = false
        }
    }

    fun unlinkEvent() {
        val eventId = state.value.linkedEvent?.eventId ?: return
        viewModelScope.launch { calendarRepo.linkActivity(eventId, null, null) }
    }
}

/**
 * The [HrZoneModel] the zone table reads against (P14.6, §3.9): `hrRest`/`hrMax` per PLAN P2.9
 * (`restingHrManual ?: 60`, `maxHrManual ?: estimatedMaxHr(age)`), then the athlete's own scheme
 * (manual bounds / LTHR / Karvonen) when a profile exists, else the Karvonen default.
 */
private fun hrZoneModelFor(profile: Profile?, today: LocalDate): HrZoneModel {
    val hrRest = profile?.restingHrManual ?: FALLBACK_HR_REST
    val hrMax = profile?.let { it.estimatedMaxHr(it.ageYears(today)) } ?: FALLBACK_HR_MAX
    val bounds = HrBounds(hrMax = hrMax, hrRest = hrRest)
    return if (profile != null) {
        HrZoneModel.resolve(profile, bounds)
    } else {
        val boundaries = HrZoneModel.KARVONEN_FRACTIONS.map { bounds.hrRest + TrimpDefaults.roundHalfUp(bounds.reserve * it) }
        HrZoneModel(HrZoneScheme.HRR_KARVONEN, HrZoneModel.zonesOf(boundaries, bounds), bounds)
    }
}
