package com.myhealth.domain.engine.strength.video

import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.model.StrengthWorkoutKind

/** One checked row of the import screen, ready to save (P26.4). */
data class VideoRow(
    val exercise: Exercise,
    val sets: Int,
    val reps: Int?,
    val seconds: Int?,
    val perSide: Boolean,
    val sourceLine: String?,
)

/**
 * Checked import rows → a video workout (P26.4). Rows get exactly one of reps / seconds (the
 * editor's rule): seconds win when the list gave both; with neither, a timed exercise gets 30 s and
 * any other 10 reps. The type is derived from the moves ([deriveKind]) unless the owner chose one.
 */
object VideoWorkoutBuilder {

    const val DEFAULT_SECONDS = 30
    const val DEFAULT_REPS = 10
    const val PER_SIDE_NOTE = "each side"

    fun row(row: VideoRow, workoutId: Long, orderIndex: Int): StrengthWorkoutExercise {
        val seconds = row.seconds ?: if (row.reps == null && row.exercise.isTimed) DEFAULT_SECONDS else null
        val reps = if (seconds != null) null else row.reps ?: DEFAULT_REPS
        return StrengthWorkoutExercise(
            id = 0L,
            workoutId = workoutId,
            orderIndex = orderIndex,
            exerciseId = row.exercise.id,
            sets = row.sets.coerceAtLeast(1),
            reps = reps,
            seconds = seconds,
            isBodyweight = row.exercise.isBodyweightOnly,
            note = if (row.perSide) PER_SIDE_NOTE else null,
            sourceLine = row.sourceLine,
        )
    }

    fun build(
        base: StrengthWorkout?,
        name: String,
        videoUrl: String,
        videoTitle: String?,
        durationMin: Int?,
        sourceText: String?,
        rows: List<VideoRow>,
        kind: StrengthWorkoutKind?,
        nowMillis: Long,
    ): StrengthWorkout {
        val id = base?.id ?: 0L
        return StrengthWorkout(
            id = id,
            name = name.trim(),
            kind = kind ?: deriveKind(rows),
            templateId = null,
            isBuiltIn = false,
            notes = base?.notes,
            exercises = rows.mapIndexed { index, r -> row(r, id, index) },
            createdAtMillis = base?.createdAtMillis ?: nowMillis,
            updatedAtMillis = nowMillis,
            useInSuggestions = base?.useInSuggestions ?: true,
            videoUrl = VideoLink.normalise(videoUrl),
            videoTitle = videoTitle?.trim()?.takeIf { it.isNotEmpty() },
            durationMin = durationMin?.takeIf { it > 0 },
            sourceText = sourceText?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The workout type from the moves, weighted by sets (primary muscles 1, secondary ½, spread over
     * the exercise's muscles): mostly mobility exercises → a mobility type (lower / upper by the same
     * 65 % shares, else full); otherwise lower ≥ 65 % → LOWER, upper ≥ 65 % → UPPER, core ≥ 50 % →
     * CORE, else FULL. No rows → FULL.
     */
    fun deriveKind(rows: List<VideoRow>): StrengthWorkoutKind {
        if (rows.isEmpty()) return StrengthWorkoutKind.FULL
        var lower = 0.0
        var upper = 0.0
        var core = 0.0
        var mobilitySets = 0
        var allSets = 0
        rows.forEach { r ->
            val sets = r.sets.coerceAtLeast(1)
            allSets += sets
            if (r.exercise.isMobility) mobilitySets += sets
            val weights = r.exercise.primary.associateWith { 1.0 } + (r.exercise.secondary - r.exercise.primary).associateWith { 0.5 }
            val total = weights.values.sum().takeIf { it > 0 } ?: return@forEach
            weights.forEach { (muscle, w) ->
                val share = sets * w / total
                when {
                    muscle in CORE -> core += share
                    muscle.isLowerBody -> lower += share
                    else -> upper += share
                }
            }
        }
        val sum = lower + upper + core
        if (sum <= 0.0) return StrengthWorkoutKind.FULL
        val lowerShare = lower / sum
        val upperShare = upper / sum
        val coreShare = core / sum
        return if (mobilitySets * 2 >= allSets) {
            when {
                lowerShare >= SIDE_SHARE -> StrengthWorkoutKind.MOBILITY_LOWER
                upperShare >= SIDE_SHARE -> StrengthWorkoutKind.MOBILITY_UPPER
                else -> StrengthWorkoutKind.MOBILITY_FULL
            }
        } else {
            when {
                lowerShare >= SIDE_SHARE -> StrengthWorkoutKind.LOWER
                upperShare >= SIDE_SHARE -> StrengthWorkoutKind.UPPER
                coreShare >= CORE_SHARE -> StrengthWorkoutKind.CORE
                else -> StrengthWorkoutKind.FULL
            }
        }
    }

    private const val SIDE_SHARE = 0.65
    private const val CORE_SHARE = 0.5
    private val CORE = setOf(MuscleGroup.ABS, MuscleGroup.OBLIQUES, MuscleGroup.LOWER_BACK)
}
