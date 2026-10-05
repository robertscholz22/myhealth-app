package com.myhealth.sync

import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.repository.NutritionRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome

/**
 * Recomputes the `nutrition_target_snapshot` rows of the `[today - 1, today + 7]` window (PLAN
 * P4.12) through [NutritionRepository.ensureTarget], which itself skips any day whose `inputsHash`
 * is unchanged. P20.2: the body of the Android `TargetRecomputeWorker`, in common code so the iOS
 * background task runs the same loop.
 */
class TargetRecomputeService(
    private val nutritionRepo: NutritionRepository,
    private val clock: PlatformClock,
) {

    /** Counts of one run; [failure] is the last error that is worth a retry, if any. */
    data class Result(val recomputed: Int, val skipped: Int, val failure: AppError?)

    suspend fun recompute(): Result {
        val today = clock.todayEpochDay()
        var recomputed = 0
        var skipped = 0
        var failure: AppError? = null
        for (day in (today + PAST_DAYS)..(today + FUTURE_DAYS)) {
            when (val outcome = nutritionRepo.ensureTarget(day)) {
                is Outcome.Ok -> recomputed++
                is Outcome.Err -> when (outcome.error) {
                    // No profile yet (onboarding not finished): there is nothing to compute, and
                    // retrying would never help — the profile write itself re-enqueues the recompute.
                    is AppError.Validation -> skipped++
                    else -> failure = outcome.error
                }
            }
        }
        return Result(recomputed, skipped, failure)
    }

    companion object {
        /** The window of §P4.12: yesterday through a week out. */
        const val PAST_DAYS: Long = -1L
        const val FUTURE_DAYS: Long = 7L
    }
}
