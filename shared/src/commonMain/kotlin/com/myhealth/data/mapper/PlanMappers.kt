package com.myhealth.data.mapper

import com.myhealth.data.db.entity.GoalEntity
import com.myhealth.data.db.entity.PlannedSessionEntity
import com.myhealth.data.db.entity.SuggestedSessionEntity
import com.myhealth.data.db.entity.SuggestionBatchEntity
import com.myhealth.data.db.entity.TrainingPlanEntity
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.RationaleEntry
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.SuggestionBatch
import com.myhealth.domain.model.TrainingPlan
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `training_plan`, `planned_session`, `suggestion_batch`, `suggested_session` and `goal` ⇄ the
 * domain plan models (PLAN §2.2.4 / P1.6).
 */

private val json = Json { ignoreUnknownKeys = true }

// ---- training_plan ⇄ TrainingPlan ---------------------------------------------------------------

fun TrainingPlanEntity.toDomain(): TrainingPlan = TrainingPlan(
    id = id,
    name = name,
    startDay = startDay,
    endDay = endDay,
    status = status,
    primaryGoalId = primaryGoalId,
    notes = notes,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun TrainingPlan.toEntity(): TrainingPlanEntity = TrainingPlanEntity(
    id = id,
    name = name,
    startDay = startDay,
    endDay = endDay,
    status = status,
    primaryGoalId = primaryGoalId,
    notes = notes,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

// ---- planned_session ⇄ PlannedSession -----------------------------------------------------------

fun PlannedSessionEntity.toDomain(): PlannedSession = PlannedSession(
    id = id,
    planId = planId,
    day = day,
    startMinuteOfDay = startMinuteOfDay,
    sportType = sportType,
    sessionType = sessionType,
    intensity = intensity,
    targetDurationMin = targetDurationMin,
    targetDistanceMeters = targetDistanceMeters,
    targetPaceSecPerKm = targetPaceSecPerKm,
    estimatedTrimp = estimatedTrimp,
    description = description,
    rationale = rationale,
    status = status,
    locked = locked,
    linkedActivityId = linkedActivityId,
    sourceSuggestionId = sourceSuggestionId,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    structureJson = structureJson,
    workoutId = workoutId,
)

fun PlannedSession.toEntity(): PlannedSessionEntity = PlannedSessionEntity(
    id = id,
    planId = planId,
    day = day,
    startMinuteOfDay = startMinuteOfDay,
    sportType = sportType,
    sessionType = sessionType,
    intensity = intensity,
    targetDurationMin = targetDurationMin,
    targetDistanceMeters = targetDistanceMeters,
    targetPaceSecPerKm = targetPaceSecPerKm,
    estimatedTrimp = estimatedTrimp,
    description = description,
    rationale = rationale,
    status = status,
    locked = locked,
    linkedActivityId = linkedActivityId,
    sourceSuggestionId = sourceSuggestionId,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    structureJson = structureJson,
    workoutId = workoutId,
)

// ---- suggestion_batch ⇄ SuggestionBatch ---------------------------------------------------------

fun SuggestionBatchEntity.toDomain(): SuggestionBatch = SuggestionBatch(
    id = id,
    generatedAtMillis = generatedAtMillis,
    horizonStartDay = horizonStartDay,
    horizonEndDay = horizonEndDay,
    phase = phase,
    weeklyLoadTarget = weeklyLoadTarget,
    inputsHash = inputsHash,
    status = status,
)

fun SuggestionBatch.toEntity(): SuggestionBatchEntity = SuggestionBatchEntity(
    id = id,
    generatedAtMillis = generatedAtMillis,
    horizonStartDay = horizonStartDay,
    horizonEndDay = horizonEndDay,
    phase = phase,
    weeklyLoadTarget = weeklyLoadTarget,
    inputsHash = inputsHash,
    status = status,
)

// ---- suggested_session ⇄ SuggestedSession (rationaleJson ⇄ List<RationaleEntry>) ----------------

@Serializable
private data class RationaleDto(val ruleId: String, val text: String)

private fun List<RationaleEntry>.toRationaleJson(): String =
    json.encodeToString(map { RationaleDto(it.ruleId, it.text) })

private fun String.toRationale(): List<RationaleEntry> =
    if (isBlank()) emptyList() else json.decodeFromString<List<RationaleDto>>(this)
        .map { RationaleEntry(it.ruleId, it.text) }

fun SuggestedSessionEntity.toDomain(): SuggestedSession = SuggestedSession(
    id = id,
    batchId = batchId,
    day = day,
    sportType = sportType,
    sessionType = sessionType,
    intensity = intensity,
    targetDurationMin = targetDurationMin,
    targetDistanceMeters = targetDistanceMeters,
    estimatedTrimp = estimatedTrimp,
    score = score,
    rationale = rationaleJson.toRationale(),
    status = status,
    targetPaceSecPerKm = targetPaceSecPerKm,
    structureJson = structureJson,
    workoutTemplateId = workoutTemplateId,
    workoutId = workoutId,
)

fun SuggestedSession.toEntity(): SuggestedSessionEntity = SuggestedSessionEntity(
    id = id,
    batchId = batchId,
    day = day,
    sportType = sportType,
    sessionType = sessionType,
    intensity = intensity,
    targetDurationMin = targetDurationMin,
    targetDistanceMeters = targetDistanceMeters,
    estimatedTrimp = estimatedTrimp,
    score = score,
    rationaleJson = rationale.toRationaleJson(),
    status = status,
    targetPaceSecPerKm = targetPaceSecPerKm,
    structureJson = structureJson,
    workoutTemplateId = workoutTemplateId,
    workoutId = workoutId,
)

// ---- goal ⇄ Goal ----------------------------------------------------------------------------------

fun GoalEntity.toDomain(): Goal = Goal(
    id = id,
    type = type,
    title = title,
    targetDay = targetDay,
    targetDistanceMeters = targetDistanceMeters,
    targetTimeSec = targetTimeSec,
    targetWeightKg = targetWeightKg,
    targetValue = targetValue,
    priority = priority,
    status = status,
    linkedEventId = linkedEventId,
    notes = notes,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    isRace = isRace,
)

fun Goal.toEntity(): GoalEntity = GoalEntity(
    id = id,
    type = type,
    title = title,
    targetDay = targetDay,
    targetDistanceMeters = targetDistanceMeters,
    targetTimeSec = targetTimeSec,
    targetWeightKg = targetWeightKg,
    targetValue = targetValue,
    priority = priority,
    status = status,
    linkedEventId = linkedEventId,
    notes = notes,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    isRace = isRace,
)
