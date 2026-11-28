package com.myhealth.data.ai

import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.UserExercise
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.model.StrengthWorkoutKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What `ai_change.beforeJson` keeps so an applied change can be undone. */
@Serializable
sealed class AiUndo {
    open val kind: String? get() = null
    open val localKey: String? get() = null

    /** The AI created this row: undo deletes it (an exercise in use is archived instead). */
    @Serializable
    @SerialName("created")
    data class Created(override val kind: String, override val localKey: String) : AiUndo()

    /** One of the owner's exercises as it was before the AI corrected it. */
    @Serializable
    @SerialName("exercise")
    data class ExerciseBefore(val exercise: ExerciseJson) : AiUndo() {
        override val kind get() = "EXERCISE"
        override val localKey get() = exercise.id
    }

    /** The equipment set before, as `profile.availableEquipmentJson` (null = everything). */
    @Serializable
    @SerialName("equipment")
    data class EquipmentBefore(val json: String?) : AiUndo()

    /** A workout as it was before a change or delete: undo puts it back. */
    @Serializable
    @SerialName("workout")
    data class WorkoutBefore(val workout: WorkoutJson) : AiUndo() {
        override val kind get() = "WORKOUT"
        override val localKey get() = workout.id.toString()
    }

    /** A planned session as it was before a change or delete. */
    @Serializable
    @SerialName("session")
    data class SessionBefore(val session: SessionJson) : AiUndo() {
        override val kind get() = "SESSION"
        override val localKey get() = session.id.toString()
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; classDiscriminator = "t" }
        fun encode(undo: AiUndo): String = JSON.encodeToString(serializer(), undo)
        fun decode(json: String): AiUndo? = runCatching { JSON.decodeFromString(serializer(), json) }.getOrNull()
    }
}

@Serializable
data class SessionJson(
    val id: Long, val planId: Long?, val day: Long, val start: Int?, val sport: String, val type: String, val intensity: String,
    val durationMin: Int?, val distanceM: Double?, val pace: Int?, val trimp: Double?, val description: String?,
    val rationale: String?, val status: String, val locked: Boolean, val activityId: Long?, val suggestionId: Long?,
    val createdAt: Long, val updatedAt: Long, val structure: String?, val workoutId: Long?,
) {
    fun toModel() = PlannedSession(
        id = id, planId = planId, day = day, startMinuteOfDay = start, sportType = SportType.valueOf(sport),
        sessionType = SessionType.valueOf(type), intensity = Intensity.valueOf(intensity), targetDurationMin = durationMin,
        targetDistanceMeters = distanceM, targetPaceSecPerKm = pace, estimatedTrimp = trimp, description = description,
        rationale = rationale, status = PlannedStatus.valueOf(status), locked = locked, linkedActivityId = activityId,
        sourceSuggestionId = suggestionId, createdAtMillis = createdAt, updatedAtMillis = updatedAt, structureJson = structure,
        workoutId = workoutId,
    )

    companion object {
        fun of(s: PlannedSession) = SessionJson(
            s.id, s.planId, s.day, s.startMinuteOfDay, s.sportType.name, s.sessionType.name, s.intensity.name, s.targetDurationMin,
            s.targetDistanceMeters, s.targetPaceSecPerKm, s.estimatedTrimp, s.description, s.rationale, s.status.name, s.locked,
            s.linkedActivityId, s.sourceSuggestionId, s.createdAtMillis, s.updatedAtMillis, s.structureJson, s.workoutId,
        )
    }
}

@Serializable
data class WorkoutJson(
    val id: Long, val name: String, val kind: String, val templateId: String?, val builtIn: Boolean, val notes: String?,
    val rows: List<Row>, val createdAt: Long, val updatedAt: Long, val useInSuggestions: Boolean, val videoUrl: String?,
    val videoTitle: String?, val durationMin: Int?, val sourceText: String?,
) {
    @Serializable
    data class Row(
        val order: Int, val exerciseId: String, val sets: Int, val reps: Int?, val seconds: Int?, val loadKg: Double?,
        val bodyweight: Boolean, val restSec: Int?, val note: String?, val sourceLine: String?,
    )

    fun toModel() = StrengthWorkout(
        id = id, name = name, kind = StrengthWorkoutKind.valueOf(kind), templateId = templateId, isBuiltIn = builtIn, notes = notes,
        exercises = rows.map { r ->
            StrengthWorkoutExercise(
                id = 0L, workoutId = id, orderIndex = r.order, exerciseId = r.exerciseId, sets = r.sets, reps = r.reps,
                seconds = r.seconds, loadKg = r.loadKg, isBodyweight = r.bodyweight, restSec = r.restSec, note = r.note,
                sourceLine = r.sourceLine,
            )
        },
        createdAtMillis = createdAt, updatedAtMillis = updatedAt, useInSuggestions = useInSuggestions, videoUrl = videoUrl,
        videoTitle = videoTitle, durationMin = durationMin, sourceText = sourceText,
    )

    companion object {
        fun of(w: StrengthWorkout) = WorkoutJson(
            w.id, w.name, w.kind.name, w.templateId, w.isBuiltIn, w.notes,
            w.exercises.map { Row(it.orderIndex, it.exerciseId, it.sets, it.reps, it.seconds, it.loadKg, it.isBodyweight, it.restSec, it.note, it.sourceLine) },
            w.createdAtMillis, w.updatedAtMillis, w.useInSuggestions, w.videoUrl, w.videoTitle, w.durationMin, w.sourceText,
        )
    }
}

@Serializable
data class ExerciseJson(
    val id: String, val name: String, val primary: List<String>, val secondary: List<String>, val equipment: String,
    val pattern: String, val unilateral: Boolean, val timed: Boolean, val cue: String, val notes: String?,
    val archived: Boolean, val createdAt: Long, val updatedAt: Long,
) {
    fun toModel() = UserExercise(
        Exercise(
            id = id, name = name, primary = primary.map(MuscleGroup::valueOf).toSet(), secondary = secondary.map(MuscleGroup::valueOf).toSet(),
            equipment = Equipment.valueOf(equipment), pattern = MovementPattern.valueOf(pattern), unilateral = unilateral, isTimed = timed, cue = cue,
        ),
        notes, archived, createdAt, updatedAt,
    )

    companion object {
        fun of(u: UserExercise) = u.exercise.let { e ->
            ExerciseJson(
                e.id, e.name, e.primary.map { it.name }, e.secondary.map { it.name }, e.equipment.name, e.pattern.name,
                e.unilateral, e.isTimed, e.cue, u.notes, u.archived, u.createdAtMillis, u.updatedAtMillis,
            )
        }
    }
}
