package com.myhealth.ui.training

import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.SuggestionBatch
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.ui.common.UiMessage

/**
 * One row of the review list: either a suggested session or the rest day the suggester left free
 * (PLAN §4.2 "Suggestion review" — a rest day is a deliberate output of §3.5.3's C3, so it is
 * shown as a row rather than as a gap the reader has to notice).
 */
data class SuggestionRow(val day: Long, val session: SuggestedSession?) {
    val isRest: Boolean get() = session == null
}

/** ViewModel state for [SuggestionReviewScreen]. */
data class SuggestionReviewUiState(
    val isLoading: Boolean = true,
    val batch: SuggestionBatch? = null,
    val rows: List<SuggestionRow> = emptyList(),
    /** Per suggested-session id; a session not in the map is accepted (the default, §4.2). */
    val accepted: Map<Long, Boolean> = emptyMap(),
    /** Unlocked PLANNED sessions in the horizon that accepting will replace (BUG-10). */
    val replaceableCount: Int = 0,
    /** Load of the locked and hand-planned sessions in the horizon (NOTE-19). */
    val fixedLoad: Double = 0.0,
    val isWorking: Boolean = false,
    val message: UiMessage? = null,
    /** One-shot: set once the batch has been accepted/rejected, so the screen can go back. */
    val done: Boolean = false,
    /** A profile-only zone model (P14.6, §4.2): the chip only needs a zone's bpm range, not a
     * measured pace band, so this is the lightweight resolution — see `lightweightHrZoneModel`. */
    val hrZoneModel: HrZoneModel? = null,
) {
    val phase: TrainingPhase? get() = batch?.phase

    val weeklyTarget: Double get() = batch?.weeklyLoadTarget ?: 0.0

    val sessions: List<SuggestedSession> get() = rows.mapNotNull { it.session }

    /** Σ estimated load over **every** suggestion — what the week costs if it is all accepted. */
    val totalSuggestedLoad: Double get() = sessions.sumOf { it.estimatedTrimp }

    /** Σ estimated load over the sessions still ticked — what accepting right now would cost. */
    val selectedLoad: Double get() = sessions.filter { isAccepted(it.id) }.sumOf { it.estimatedTrimp }

    val restDayCount: Int get() = rows.count { it.isRest }

    fun isAccepted(sessionId: Long): Boolean = accepted[sessionId] ?: true

    val selectedIds: List<Long> get() = sessions.map { it.id }.filter { isAccepted(it) }

    val rejectedIds: List<Long> get() = sessions.map { it.id }.filterNot { isAccepted(it) }

    val isEmpty: Boolean get() = !isLoading && rows.isEmpty()

    /** A reviewed batch is history: the screen offers "Regenerate" instead of "Accept". */
    val isReviewable: Boolean
        get() = batch != null && batch.status == SuggestionStatus.PROPOSED && sessions.isNotEmpty()
}

/**
 * Every day of the batch's horizon as a row, in day order: the sessions proposed for that day
 * (best score first — the §3.5.6 ordering the engine already placed them in), and a rest row for
 * a day that got nothing. Pure, so the derivation is unit-tested without a repository.
 *
 * Sessions the reviewer already rejected are dropped, so rejecting the only session on a day turns
 * it back into the rest day it effectively is.
 */
fun suggestionRows(batch: SuggestionBatch?, sessions: List<SuggestedSession>): List<SuggestionRow> {
    if (batch == null) return emptyList()
    val open = sessions.filter { it.status != SuggestionStatus.REJECTED }
    val byDay = open.groupBy { it.day }
    return (batch.horizonStartDay until batch.horizonEndDay).flatMap { day ->
        val forDay = byDay[day]
            ?.sortedWith(compareByDescending<SuggestedSession> { it.score }.thenBy { it.sessionType.ordinal })
            .orEmpty()
        if (forDay.isEmpty()) {
            listOf(SuggestionRow(day = day, session = null))
        } else {
            forDay.map { SuggestionRow(day = day, session = it) }
        }
    }
}
