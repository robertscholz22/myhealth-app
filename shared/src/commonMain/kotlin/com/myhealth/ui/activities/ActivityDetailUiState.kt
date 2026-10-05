package com.myhealth.ui.activities

import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.EventOccurrence

/** ViewModel state for [ActivityDetailScreen] (PLAN §4.2 Activity detail, P2.9/P3.7/P14.6). */
data class ActivityDetailUiState(
    val isLoading: Boolean = true,
    val activity: ActivitySession? = null,
    /** Minutes per named zone, Z1…Z5 in order (P14.6: [HrZoneModel]-driven, replacing the raw
     * fixed-band table); empty when there is no HR stream. */
    val hrZoneMinutes: List<Double> = emptyList(),
    /** The zone model minutes/bpm ranges are read against (P14.6, §3.9). */
    val hrZoneModel: HrZoneModel? = null,
    /** The target zone (`1..5`) of the planned session this activity is linked to, if any and if
     * it has one (P14.6, §4.2: "when the activity is linked to a planned session with a target
     * zone, mark that row"). */
    val targetZoneIndex: Int? = null,
    val showDeleteConfirm: Boolean = false,
    /** Set once the delete completes, so the screen can navigate back. */
    val deleted: Boolean = false,
    /** The calendar event linked to this activity, if any (P3.7). */
    val linkedEvent: EventOccurrence? = null,
    /** This activity's day's other events — the "Link to event…" picker's candidate list. */
    val dayEvents: List<EventOccurrence> = emptyList(),
    val showEventPicker: Boolean = false,
    /** The current FTP estimate (P12.2/P12.4), resolved the same way `GoalsViewModel` does — the
     * power card's IF/TSS rows are `null` until this is available. */
    val ftp: FtpEstimate? = null,
) {
    val minHr: Int? get() = activity?.streams?.hr?.filterNotNull()?.minOrNull()
    val avgHrFromStream: Int? get() = activity?.streams?.hr?.filterNotNull()
        ?.let { samples -> if (samples.isEmpty()) null else samples.sum() / samples.size }
    val maxHrFromStream: Int? get() = activity?.streams?.hr?.filterNotNull()?.maxOrNull()
    val hasHrZones: Boolean get() = hrZoneMinutes.isNotEmpty() && hrZoneMinutes.any { it > 0.0 }
}
