package com.myhealth.data.repository

import com.myhealth.domain.engine.bike.BikeDefaults
import com.myhealth.domain.engine.bike.FtpEstimator
import com.myhealth.domain.engine.load.HrBounds
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.engine.running.PaceRun
import com.myhealth.domain.engine.running.PaceZoneBand
import com.myhealth.domain.engine.running.PaceZoneEngine
import com.myhealth.domain.engine.running.PaceZoneInput
import com.myhealth.domain.engine.running.RiegelPredictor
import com.myhealth.domain.engine.running.VdotCalculator
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.RideBestRepository
import com.myhealth.domain.repository.RunningBestRepository
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.util.epochDayDate
import kotlinx.coroutines.flow.first

/**
 * The three P14.3 inputs of `SuggestionInput` (PLAN §3.11): the VDOT every Daniels pace comes from,
 * the measured pace band per heart-rate zone, and the FTP every bike power target is a share of.
 *
 * All three are "unknown" by default, and unknown is a perfectly good answer: the suggester then
 * builds zone-only structures and the week is byte-identical to the pre-P14 one.
 */
data class SuggestionPaceInputs(
    val vdot: Double? = null,
    val paceBands: List<PaceZoneBand> = emptyList(),
    val ftpWatts: Int? = null,
) {
    companion object {
        val EMPTY: SuggestionPaceInputs = SuggestionPaceInputs()
    }
}

/**
 * Assembles [SuggestionPaceInputs] from the repositories, exactly the way the rest of the app
 * already resolves each number (P14.3):
 *
 * - **VDOT** — [RiegelPredictor.pickSource] over `running_best` + [VdotCalculator.vdot], which is
 *   what the Running PRs screen shows;
 * - **pace bands** — [PaceZoneEngine] over the last [PaceZoneEngine.WINDOW_DAYS] days of runs
 *   (streams when the activity has them, its summary when it does not), against the athlete's
 *   [HrZoneModel];
 * - **FTP** — [FtpEstimator] over the manual override, `ride_best` and the rides themselves, the
 *   same ladder `GoalsViewModel` and `LoadRecomputeService` use.
 *
 * The three optional repositories are the P14.3 wiring; when none of them is present (the P6.5 test
 * harness) the resolver is [isEnabled] `false` and reads nothing at all — not even the 90 days of
 * activities, which is the only expensive part.
 *
 * Ambiguity note: `HrBounds.compute` wants the observed maximum heart rate of the last 365 days;
 * this resolver has the last 90 days loaded and uses those, so a zone model built here can sit a
 * beat lower than the load engine's if the athlete's hardest effort is older than three months.
 * That only shifts which samples land in which zone, never a stored value.
 */
class SuggestionPaceResolver(
    private val activityRepo: ActivityRepository,
    private val settingsRepo: SettingsRepository,
    private val runningBestRepo: RunningBestRepository?,
    private val rideBestRepo: RideBestRepository?,
    private val healthRepo: HealthRepository?,
) {

    val isEnabled: Boolean get() = runningBestRepo != null || rideBestRepo != null

    suspend fun resolve(profile: Profile, todayDay: Long): SuggestionPaceInputs {
        if (!isEnabled) return SuggestionPaceInputs.EMPTY
        val sessions = activityRepo.getRange(todayDay - PaceZoneEngine.WINDOW_DAYS, todayDay)
        val vdot = resolveVdot(todayDay)
        return SuggestionPaceInputs(
            vdot = vdot,
            paceBands = resolveBands(profile, todayDay, sessions, vdot),
            ftpWatts = resolveFtp(profile, sessions, todayDay),
        )
    }

    private suspend fun resolveVdot(todayDay: Long): Double? {
        // P19.1: every effort of the window — the PR table would hide a recent slower effort.
        val bests = runningBestRepo
            ?.observeSince(todayDay - RiegelPredictor.MAX_SOURCE_AGE_DAYS)?.first() ?: return null
        val source = RiegelPredictor.pickSource(bests, todayDay) ?: return null
        return VdotCalculator.vdot(source.distanceMeters, source.timeSec.toDouble())
    }

    private suspend fun resolveBands(
        profile: Profile,
        todayDay: Long,
        sessions: List<ActivitySession>,
        vdot: Double?,
    ): List<PaceZoneBand> {
        val runs = sessions.filter { it.sportGroup == SportGroup.RUN }.map { PaceRun.of(it) }
        if (runs.isEmpty() && vdot == null) return emptyList()
        val restingHr = healthRepo
            ?.observeRange(todayDay - TrimpDefaults.REST_HR_WINDOW_DAYS + 1, todayDay)
            ?.first()
            ?.mapNotNull { it.restingHr }
            .orEmpty()
        val bounds = HrBounds.compute(
            profile = profile,
            on = todayDay.epochDayDate(),
            restingHrLast7Days = restingHr,
            observedMaxHrLast365d = sessions.mapNotNull { it.maxHr }.maxOrNull(),
        )
        return PaceZoneEngine.compute(
            PaceZoneInput(
                today = todayDay,
                runs = runs,
                zoneModel = HrZoneModel.resolve(profile, bounds),
                vdot = vdot,
                includeTreadmillInPrs = settingsRepo.settings.first().includeTreadmillInPrs,
            ),
        )
    }

    private suspend fun resolveFtp(
        profile: Profile,
        sessions: List<ActivitySession>,
        todayDay: Long,
    ): Int? {
        val since = todayDay - BikeDefaults.FTP_WINDOW_DAYS
        val bests = rideBestRepo?.getSince(since).orEmpty()
        val rides = sessions.filter { it.sportGroup == SportGroup.CYCLE && it.day >= since }
        if (profile.ftpWattsManual == null && bests.isEmpty() && rides.isEmpty()) return null
        return FtpEstimator.estimateFromSessions(profile.ftpWattsManual, bests, rides, todayDay)?.watts
    }
}
