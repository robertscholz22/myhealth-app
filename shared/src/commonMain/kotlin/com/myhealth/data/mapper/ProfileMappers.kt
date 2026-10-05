package com.myhealth.data.mapper

import com.myhealth.data.db.entity.ProfileEntity
import com.myhealth.domain.model.Profile

/**
 * `profile` ⇄ [Profile] (PLAN §2.2.1 / P1.6). A 1:1 field mapping — [Profile.preferredSportsJson]
 * is already a raw JSON string on the domain model (§2.3 does not list a richer projection for
 * it), so it passes through unchanged.
 */
fun ProfileEntity.toDomain(): Profile = Profile(
    id = id,
    displayName = displayName,
    sex = sex,
    birthDay = birthDay,
    heightCm = heightCm,
    neatLevel = neatLevel,
    goalWeightKg = goalWeightKg,
    goalPaceKgPerWeek = goalPaceKgPerWeek,
    restingHrManual = restingHrManual,
    maxHrManual = maxHrManual,
    fallbackWeightKg = fallbackWeightKg,
    sleepTargetHours = sleepTargetHours,
    preferredSportsJson = preferredSportsJson,
    mobilityOnRestDays = mobilityOnRestDays,
    ftpWattsManual = ftpWattsManual,
    indoorTrainerAvailable = indoorTrainerAvailable,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    hrZoneBoundsJson = hrZoneBoundsJson,
    lactateThresholdHrManual = lactateThresholdHrManual,
    availableEquipmentJson = availableEquipmentJson,
)

fun Profile.toEntity(): ProfileEntity = ProfileEntity(
    id = id,
    displayName = displayName,
    sex = sex,
    birthDay = birthDay,
    heightCm = heightCm,
    neatLevel = neatLevel,
    goalWeightKg = goalWeightKg,
    goalPaceKgPerWeek = goalPaceKgPerWeek,
    restingHrManual = restingHrManual,
    maxHrManual = maxHrManual,
    fallbackWeightKg = fallbackWeightKg,
    sleepTargetHours = sleepTargetHours,
    preferredSportsJson = preferredSportsJson,
    mobilityOnRestDays = mobilityOnRestDays,
    ftpWattsManual = ftpWattsManual,
    indoorTrainerAvailable = indoorTrainerAvailable,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    hrZoneBoundsJson = hrZoneBoundsJson,
    lactateThresholdHrManual = lactateThresholdHrManual,
    availableEquipmentJson = availableEquipmentJson,
)
