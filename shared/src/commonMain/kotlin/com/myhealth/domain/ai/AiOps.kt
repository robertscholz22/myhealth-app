package com.myhealth.domain.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * P28: one change the AI asked for through the relay. The relay checks it against the snapshot for
 * an immediate answer; the phone checks it again ([AiOpPlanner]) and is the one that decides.
 *
 * Enum-like fields travel as strings so an unknown value becomes a readable rejection instead of a
 * decode failure. Days are ISO dates (`2026-12-01`), start times `HH:MM`, paces `m:ss` per km.
 * References: catalog and own exercise ids (`PUSH_UP`, `USER_…`), `w12` / `s34` for workouts and
 * planned sessions, or the `ai-…` id the AI gave a row it created.
 */
@Serializable
sealed class AiOp {
    abstract val opId: String

    @Serializable
    @SerialName("create_exercise")
    data class CreateExercise(
        override val opId: String,
        val aiId: String,
        val name: String,
        val primary: List<String>,
        val secondary: List<String> = emptyList(),
        val equipment: String = "BODYWEIGHT",
        val pattern: String = "OTHER",
        val timed: Boolean = false,
        val oneSided: Boolean = false,
        val notes: String? = null,
    ) : AiOp()

    /**
     * Corrects one of the owner's exercises (made by the AI or by the owner; never the catalog or an
     * exercise shared by someone else). Fields left out stay.
     */
    @Serializable
    @SerialName("update_exercise")
    data class UpdateExercise(
        override val opId: String,
        val ref: String,
        val name: String? = null,
        val primary: List<String>? = null,
        val secondary: List<String>? = null,
        val equipment: String? = null,
        val pattern: String? = null,
        val timed: Boolean? = null,
        val oneSided: Boolean? = null,
        /** "" clears the notes. */
        val notes: String? = null,
    ) : AiOp()

    @Serializable
    @SerialName("set_equipment")
    data class SetEquipment(
        override val opId: String,
        val equipment: List<String>,
    ) : AiOp()

    @Serializable
    @SerialName("create_workout")
    data class CreateWorkout(
        override val opId: String,
        val aiId: String,
        val name: String,
        val kind: String = "CUSTOM",
        val rows: List<AiWorkoutRow>,
        val notes: String? = null,
        /** P28: the training video this workout follows (https; YouTube links are normalised). */
        val videoUrl: String? = null,
        val videoTitle: String? = null,
        /** The video's length; the workout then counts that many minutes. */
        val videoMinutes: Int? = null,
        /** The text the exercises were taken from (description, chapters, transcript excerpt). */
        val sourceText: String? = null,
    ) : AiOp()

    @Serializable
    @SerialName("update_workout")
    data class UpdateWorkout(
        override val opId: String,
        val ref: String,
        val name: String? = null,
        val kind: String? = null,
        val rows: List<AiWorkoutRow>? = null,
        val notes: String? = null,
        /** P28: the training video this workout follows (https; YouTube links are normalised); "" removes the video. */
        val videoUrl: String? = null,
        val videoTitle: String? = null,
        /** The video's length; the workout then counts that many minutes. */
        val videoMinutes: Int? = null,
        /** The text the exercises were taken from (description, chapters, transcript excerpt). */
        val sourceText: String? = null,
    ) : AiOp()

    @Serializable
    @SerialName("delete_workout")
    data class DeleteWorkout(override val opId: String, val ref: String) : AiOp()

    @Serializable
    @SerialName("schedule_session")
    data class ScheduleSession(
        override val opId: String,
        val aiId: String,
        val session: AiSessionFields,
    ) : AiOp()

    @Serializable
    @SerialName("update_session")
    data class UpdateSession(
        override val opId: String,
        val ref: String,
        val session: AiSessionFields,
    ) : AiOp()

    @Serializable
    @SerialName("delete_session")
    data class DeleteSession(override val opId: String, val ref: String) : AiOp()

    companion object {
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            explicitNulls = false
        }

        /** One op from the relay's queue, or null when it is not an op this app version knows. */
        fun decode(element: JsonElement): AiOp? =
            runCatching { JSON.decodeFromJsonElement(serializer(), element) }.getOrNull()
    }
}

/** One row of a workout: [reps] or [seconds] (timed exercises), optional load in kg. */
@Serializable
data class AiWorkoutRow(
    val exerciseId: String,
    val sets: Int,
    val reps: Int? = null,
    val seconds: Int? = null,
    val loadKg: Double? = null,
    val restSec: Int? = null,
    val note: String? = null,
    /** The line of the video's text this row was taken from, e.g. "0:45 Glute bridge x15". */
    val sourceLine: String? = null,
)

/**
 * The fields of a planned session. For a new session [day] and [sport] are required; for an update
 * every field left out keeps its value.
 */
@Serializable
data class AiSessionFields(
    val day: String? = null,
    val start: String? = null,
    val sport: String? = null,
    val sessionType: String? = null,
    val intensity: String? = null,
    val durationMin: Int? = null,
    val distanceKm: Double? = null,
    val pace: String? = null,
    val workout: String? = null,
    val description: String? = null,
    val rationale: String? = null,
)
