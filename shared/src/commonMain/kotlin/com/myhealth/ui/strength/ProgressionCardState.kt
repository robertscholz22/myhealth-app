package com.myhealth.ui.strength

import com.myhealth.domain.engine.strength.ProgressionDefaults
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.StrengthSetLog
import com.myhealth.ui.common.usText
import kotlinx.datetime.LocalDate

/**
 * [ExerciseDetailScreen]'s Progression card (PLAN §P16 "Where it shows", P16.2): today's
 * prescription, when it was last updated, and the recent session lines below it.
 */
data class ProgressionCardState(
    val prescription: ExercisePrescription? = null,
    /** Epoch day of `exercise_progress.updatedDay` — `null` until any feedback has been given. */
    val updatedDay: Long? = null,
    /** Up to 10 sessions, most recent first, already formatted as "day · sets × reps @ kg". */
    val sessions: List<String> = emptyList(),
)

/**
 * [logs] (most recent day first, as [com.myhealth.domain.repository.StrengthRepository.getRecentSetLogs]
 * returns them) grouped into up to [maxSessions] sessions — one line per distinct day, summarised
 * from that day's set rows via [prescriptionLabel]. A session's "sets" count and its reps/seconds/
 * load come from its first logged set: the sheet writes every set of one exercise with the same
 * prescription, so the first row already describes the whole session.
 */
fun recentSessionLines(logs: List<StrengthSetLog>, maxSessions: Int = 10): List<String> =
    logs.groupBy { it.day }
        .entries
        .sortedByDescending { it.key }
        .take(maxSessions)
        .map { (day, sets) -> "${formatSessionDay(day)} · ${sessionSummary(sets)}" }

/** The set count plus its reps/seconds/load, formatted like [prescriptionLabel] but never marked
 * "~" — a logged session is a fact, never an estimate. */
private fun sessionSummary(sets: List<StrengthSetLog>): String {
    val first = sets.first()
    val prescription = ExercisePrescription(
        exerciseId = first.exerciseId,
        loadKg = first.loadKg,
        reps = first.reps,
        seconds = first.seconds,
        isEstimated = false,
        perHand = ProgressionDefaults.isPerHand(first.exerciseId) && first.loadKg != null,
    )
    return prescriptionLabel(sets.size, prescription)
}

/** "12 Sep" from an epoch day (mirrors `SettingsScreen.formatFtpBasisDate`'s pattern/locale). */
private fun formatSessionDay(epochDay: Long): String =
    LocalDate.fromEpochDays(epochDay).usText(DAY_FORMAT)

private const val DAY_FORMAT = "d MMM"
