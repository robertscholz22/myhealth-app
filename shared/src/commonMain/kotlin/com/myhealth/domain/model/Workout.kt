package com.myhealth.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A structured workout as stored in `planned_session.structureJson` /
 * `suggested_session.structureJson` (PLAN §3.11).
 *
 * [version] is the blob's own format version, not the database version: a structure written by a
 * newer build decodes to `null` rather than throwing, exactly like an unknown enum name decodes to
 * its last-resort member — an older build then shows the session without its structure instead of
 * crashing on it.
 */
@Serializable
data class WorkoutStructure(
    val version: Int = CURRENT_VERSION,
    /** The `IntervalCatalog` / `StrengthTemplates` id this was built from, when it came from one. */
    val templateId: String? = null,
    val steps: List<WorkoutStep> = emptyList(),
) {
    companion object {
        /** The only version this build writes and the only one it accepts. */
        const val CURRENT_VERSION: Int = 1
    }
}

/**
 * One step of a [WorkoutStructure] (PLAN §3.11).
 *
 * A [WorkoutStepKind.REPEAT] step carries [children] and a [repeat] count; **exactly one level of
 * nesting** is allowed, which is enough for `2 × (10 × 30/30)` and keeps every renderer a simple
 * two-level loop. [WorkoutStructureCodec] rejects a deeper tree.
 *
 * A step is described either by [durationSec] or by [distanceMeters]; its target is whatever
 * [target] names — a zone, a pace band in s/km, a power band in watts, or nothing at all.
 */
@Serializable
data class WorkoutStep(
    val kind: WorkoutStepKind,
    val repeat: Int = 1,
    val durationSec: Int? = null,
    val distanceMeters: Double? = null,
    val target: WorkoutTargetKind = WorkoutTargetKind.NONE,
    val zone: Int? = null,
    val paceLowSecPerKm: Int? = null,
    val paceHighSecPerKm: Int? = null,
    val powerLowW: Int? = null,
    val powerHighW: Int? = null,
    val children: List<WorkoutStep> = emptyList(),
    val note: String? = null,
)

/**
 * `WorkoutStructure` ⇄ the JSON column (PLAN §3.11).
 *
 * Decoding is total: an unknown [WorkoutStructure.version], a tree nested deeper than one level,
 * malformed JSON and `null` all yield `null`. Unknown keys are ignored so a field added by a later
 * build does not make the structure unreadable — only a version bump does, and that is deliberate.
 */
object WorkoutStructureCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(structure: WorkoutStructure): String = json.encodeToString(structure)

    fun decode(raw: String?): WorkoutStructure? {
        if (raw.isNullOrBlank()) return null
        val decoded = runCatching { json.decodeFromString<WorkoutStructure>(raw) }.getOrNull()
            ?: return null
        if (decoded.version != WorkoutStructure.CURRENT_VERSION) return null
        return if (isNestingValid(decoded)) decoded else null
    }

    /** One level of `REPEAT` nesting: a child may not itself carry children. */
    fun isNestingValid(structure: WorkoutStructure): Boolean =
        structure.steps.all { step -> step.children.all { it.children.isEmpty() } }
}
