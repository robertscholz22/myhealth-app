package com.myhealth.data.mapper

import com.myhealth.data.db.entity.ActivityLapEntity
import com.myhealth.data.db.entity.ActivitySessionEntity
import com.myhealth.data.db.entity.ActivitySourceRecordEntity
import com.myhealth.data.db.entity.ActivityStreamEntity
import com.myhealth.data.db.relation.ActivityWithStream
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySourceRecord
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.GeoPoint
import com.myhealth.domain.model.Lap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `activity_session`, `activity_source_record`, `activity_stream` and `activity_lap` ⇄ the
 * domain activity models (PLAN §2.2.2, §2.3 / P1.6).
 *
 * [ActivitySessionEntity] alone only carries the merged, canonical fields — streams and laps live
 * in their own tables — so [ActivitySessionEntity.toDomain] takes them as optional parameters
 * (empty/null by default) and [ActivityWithStream.toDomain] is the convenience that assembles a
 * "full" [ActivitySession] from the Room relation, matching §2.3's "list = [ActivitySummary],
 * full = [ActivitySession] with streams/laps" split.
 */

private val json = Json { ignoreUnknownKeys = true }

// ---- CSV helpers (kept local to this file — no Android imports needed) ------------------------

private fun List<String>.toCsv(): String = joinToString(",")

private fun String.csvToStringList(): List<String> =
    if (isBlank()) emptyList() else split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun List<ActivitySource>.toSourcesCsv(): String = joinToString(",") { it.name }

private fun String.csvToSources(): List<ActivitySource> =
    csvToStringList().mapNotNull { name -> ActivitySource.entries.firstOrNull { it.name == name } }

// ---- activity_session ⇄ ActivitySession / ActivitySummary -------------------------------------

fun ActivitySessionEntity.toDomain(
    streams: ActivityStreams? = null,
    laps: List<Lap> = emptyList(),
): ActivitySession = ActivitySession(
    id = id,
    startAtMillis = startAtMillis,
    endAtMillis = endAtMillis,
    day = day,
    sportType = sportType,
    sportGroup = sportGroup,
    title = title,
    durationSec = durationSec,
    elapsedSec = elapsedSec,
    distanceMeters = distanceMeters,
    activeEnergyKcal = activeEnergyKcal,
    totalEnergyKcal = totalEnergyKcal,
    avgHr = avgHr,
    maxHr = maxHr,
    avgSpeedMps = avgSpeedMps,
    maxSpeedMps = maxSpeedMps,
    avgCadenceSpm = avgCadenceSpm,
    elevationGainM = elevationGainM,
    avgPowerW = avgPowerW,
    maxPowerW = maxPowerW,
    normalizedPowerW = normalizedPowerW,
    trimp = trimp,
    loadMethod = loadMethod,
    rpe = rpe,
    note = note,
    primarySource = primarySource,
    mergedSources = mergedSourcesCsv.csvToSources(),
    dedupeBucket = dedupeBucket,
    userEditedFields = userEditedFieldsCsv.csvToStringList(),
    hasStreams = hasStreams,
    streams = streams,
    laps = laps,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

/** Light projection used by list queries (§2.3) — no streams, no laps. */
fun ActivitySessionEntity.toSummary(): ActivitySummary = ActivitySummary(
    id = id,
    startAtMillis = startAtMillis,
    endAtMillis = endAtMillis,
    day = day,
    sportType = sportType,
    sportGroup = sportGroup,
    title = title,
    durationSec = durationSec,
    elapsedSec = elapsedSec,
    distanceMeters = distanceMeters,
    activeEnergyKcal = activeEnergyKcal,
    totalEnergyKcal = totalEnergyKcal,
    avgHr = avgHr,
    maxHr = maxHr,
    avgSpeedMps = avgSpeedMps,
    maxSpeedMps = maxSpeedMps,
    avgCadenceSpm = avgCadenceSpm,
    elevationGainM = elevationGainM,
    avgPowerW = avgPowerW,
    maxPowerW = maxPowerW,
    normalizedPowerW = normalizedPowerW,
    trimp = trimp,
    loadMethod = loadMethod,
    rpe = rpe,
    note = note,
    primarySource = primarySource,
    mergedSources = mergedSourcesCsv.csvToSources(),
    hasStreams = hasStreams,
)

/** Drops [ActivitySession.streams] / [ActivitySession.laps] — those map to their own tables. */
fun ActivitySession.toEntity(): ActivitySessionEntity = ActivitySessionEntity(
    id = id,
    startAtMillis = startAtMillis,
    endAtMillis = endAtMillis,
    day = day,
    sportType = sportType,
    sportGroup = sportGroup,
    title = title,
    durationSec = durationSec,
    elapsedSec = elapsedSec,
    distanceMeters = distanceMeters,
    activeEnergyKcal = activeEnergyKcal,
    totalEnergyKcal = totalEnergyKcal,
    avgHr = avgHr,
    maxHr = maxHr,
    avgSpeedMps = avgSpeedMps,
    maxSpeedMps = maxSpeedMps,
    avgCadenceSpm = avgCadenceSpm,
    elevationGainM = elevationGainM,
    avgPowerW = avgPowerW,
    maxPowerW = maxPowerW,
    normalizedPowerW = normalizedPowerW,
    trimp = trimp,
    loadMethod = loadMethod,
    rpe = rpe,
    note = note,
    primarySource = primarySource,
    mergedSourcesCsv = mergedSources.toSourcesCsv(),
    dedupeBucket = dedupeBucket,
    userEditedFieldsCsv = userEditedFields.toCsv(),
    hasStreams = hasStreams,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

/** Assembles a full [ActivitySession] from the Room relation (§2.3). */
fun ActivityWithStream.toDomain(): ActivitySession =
    activity.toDomain(streams = stream?.toDomain(), laps = laps.map { it.toDomain() })

// ---- activity_source_record ⇄ ActivitySourceRecord ---------------------------------------------

fun ActivitySourceRecordEntity.toDomain(): ActivitySourceRecord = ActivitySourceRecord(
    id = id,
    activityId = activityId,
    source = source,
    externalId = externalId,
    payloadJson = payloadJson,
    receivedAtMillis = receivedAtMillis,
    importRecordId = importRecordId,
)

fun ActivitySourceRecord.toEntity(): ActivitySourceRecordEntity = ActivitySourceRecordEntity(
    id = id,
    activityId = activityId,
    source = source,
    externalId = externalId,
    payloadJson = payloadJson,
    receivedAtMillis = receivedAtMillis,
    importRecordId = importRecordId,
)

// ---- activity_lap ⇄ Lap -------------------------------------------------------------------------

fun ActivityLapEntity.toDomain(): Lap = Lap(
    id = id,
    activityId = activityId,
    lapIndex = lapIndex,
    startAtMillis = startAtMillis,
    durationSec = durationSec,
    distanceMeters = distanceMeters,
    avgHr = avgHr,
    maxHr = maxHr,
    avgSpeedMps = avgSpeedMps,
    energyKcal = energyKcal,
)

fun Lap.toEntity(): ActivityLapEntity = ActivityLapEntity(
    id = id,
    activityId = activityId,
    lapIndex = lapIndex,
    startAtMillis = startAtMillis,
    durationSec = durationSec,
    distanceMeters = distanceMeters,
    avgHr = avgHr,
    maxHr = maxHr,
    avgSpeedMps = avgSpeedMps,
    energyKcal = energyKcal,
)

// ---- activity_stream ⇄ ActivityStreams (kotlinx-serialization JSON columns) --------------------

fun ActivityStreamEntity.toDomain(): ActivityStreams = ActivityStreams(
    sampleOffsetsSec = json.decodeFromString<List<Int>>(sampleOffsetsSecJson).toIntArray(),
    hr = hrJson?.let { json.decodeFromString<List<Int?>>(it) } ?: emptyList(),
    distanceMeters = distanceMetersJson?.let { json.decodeFromString<List<Double>>(it).toDoubleArray() },
    speedMps = speedMpsJson?.let { json.decodeFromString<List<Double>>(it).toDoubleArray() },
    cadenceSpm = cadenceSpmJson?.let { json.decodeFromString<List<Double>>(it).toDoubleArray() },
    altitudeM = altitudeMJson?.let { json.decodeFromString<List<Double>>(it).toDoubleArray() },
    latLngE7 = latLngE7Json?.let { json.decodeFromString<List<GeoPoint>>(it) },
    powerW = powerWJson?.let { json.decodeFromString<List<Int>>(it).toIntArray() },
    sampleCount = sampleCount,
    medianIntervalSec = medianIntervalSec,
)

fun ActivityStreams.toEntity(activityId: Long): ActivityStreamEntity = ActivityStreamEntity(
    activityId = activityId,
    sampleOffsetsSecJson = json.encodeToString(sampleOffsetsSec.toList()),
    hrJson = json.encodeToString(hr),
    distanceMetersJson = distanceMeters?.let { json.encodeToString(it.toList()) },
    speedMpsJson = speedMps?.let { json.encodeToString(it.toList()) },
    cadenceSpmJson = cadenceSpm?.let { json.encodeToString(it.toList()) },
    altitudeMJson = altitudeM?.let { json.encodeToString(it.toList()) },
    latLngE7Json = latLngE7?.let { json.encodeToString(it) },
    powerWJson = powerW?.let { json.encodeToString(it.toList()) },
    sampleCount = sampleCount,
    medianIntervalSec = medianIntervalSec,
)
