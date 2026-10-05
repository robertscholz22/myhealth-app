package com.myhealth.data.fit

import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySourceRecord
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.GeoPoint
import com.myhealth.domain.model.Lap
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.repository.ActivityIngestItem
import com.myhealth.domain.util.epochMillisToDay
import kotlin.math.floor
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/** The normalized snapshot stored in `activity_source_record.payloadJson` (§2.2.2) for a FIT file. */
@Serializable
internal data class FitActivityPayload(
    val externalId: String,
    val fileId: FitFileId?,
    val session: FitSession,
    val recordCount: Int,
    val lapCount: Int,
    val localTimestampOffsetSec: Long?,
)

/**
 * [FitFileData] → the `(source record, candidate session)` pair the ingestion pipeline consumes
 * (PLAN P7.2). Exactly the seam Health Connect uses (`HcMapper`): the repository never decodes a
 * payload itself, and the de-dup/merge engine of §2.4 works on the candidate's field values.
 *
 * One [ActivityIngestItem] per `session` message, so a multisport file yields several activities;
 * records and laps are partitioned by the session's time window.
 */
class FitToDomainMapper(
    private val json: Json = Json { encodeDefaults = true },
) {

    fun toIngestItems(
        data: FitFileData,
        zone: TimeZone,
        nowMillis: Long,
    ): List<ActivityIngestItem> = data.sessions.map { session ->
        val externalId = externalId(data.fileId, session.startAtMillis)
        val endAtMillis = endOf(session, data.records)
        val records = data.records.filter { it.timestampMillis in session.startAtMillis..endAtMillis }
        val laps = data.laps.filter { it.startAtMillis in session.startAtMillis..endAtMillis }
        ActivityIngestItem(
            record = toSourceRecord(data, session, externalId, records.size, laps.size, nowMillis),
            session = toSession(session, endAtMillis, records, laps, zone, nowMillis),
        )
    }

    /**
     * §2.4: `sha256(fileIdSerial + fileIdTimeCreated + startTime)`, first 32 hex chars. The three
     * parts are `|`-separated so that e.g. serial `12` + time `345` cannot collide with serial
     * `123` + time `45`; an absent part is the empty string.
     */
    fun externalId(fileId: FitFileId?, startAtMillis: Long): String {
        val serial = fileId?.serialNumber?.toString().orEmpty()
        val created = fileId?.timeCreatedMillis?.toString().orEmpty()
        return Sha256.hex("$serial|$created|$startAtMillis").take(EXTERNAL_ID_LENGTH)
    }

    private fun toSourceRecord(
        data: FitFileData,
        session: FitSession,
        externalId: String,
        recordCount: Int,
        lapCount: Int,
        receivedAtMillis: Long,
    ): ActivitySourceRecord {
        val payload = FitActivityPayload(
            externalId = externalId,
            fileId = data.fileId,
            session = session,
            recordCount = recordCount,
            lapCount = lapCount,
            localTimestampOffsetSec = data.localTimestampOffsetSec,
        )
        return ActivitySourceRecord(
            id = 0L,
            activityId = null,
            source = ActivitySource.FIT_IMPORT,
            externalId = externalId,
            payloadJson = json.encodeToString(payload),
            receivedAtMillis = receivedAtMillis,
        )
    }

    private fun toSession(
        session: FitSession,
        endAtMillis: Long,
        records: List<FitRecord>,
        laps: List<FitLap>,
        zone: TimeZone,
        nowMillis: Long,
    ): ActivitySession {
        val sportType = FitSportMap.toSportType(session.sport, session.subSport)
        val group = sportType.group
        val start = session.startAtMillis
        val elapsedSec = session.totalElapsedSec?.roundHalfUp()
            ?: session.totalTimerSec?.roundHalfUp()
            ?: ((endAtMillis - start) / 1000L).toInt()
        val durationSec = session.totalTimerSec?.roundHalfUp() ?: elapsedSec
        val streams = toStreams(start, records)
        return ActivitySession(
            id = 0L,
            startAtMillis = start,
            endAtMillis = endAtMillis,
            day = start.epochMillisToDay(zone),
            sportType = sportType,
            sportGroup = group,
            // FIT has no free-text title; the sport profile name ("Trail Run") is the closest thing.
            title = session.sportProfileName?.takeIf { it.isNotBlank() },
            durationSec = durationSec.coerceAtLeast(0),
            elapsedSec = elapsedSec.coerceAtLeast(0),
            distanceMeters = session.totalDistanceMeters,
            // Garmin writes `total_calories` as the activity's active calories.
            activeEnergyKcal = session.totalCalories?.toDouble(),
            totalEnergyKcal = null,
            avgHr = session.avgHr,
            maxHr = session.maxHr,
            avgSpeedMps = session.avgSpeedMps ?: fallbackSpeed(session.totalDistanceMeters, durationSec),
            maxSpeedMps = session.maxSpeedMps,
            avgCadenceSpm = session.avgCadenceSpm?.let { toStepsPerMinute(it, group) },
            elevationGainM = session.totalAscentM,
            avgPowerW = session.avgPowerW,
            maxPowerW = session.maxPowerW,
            normalizedPowerW = session.normalizedPowerW,
            trimp = null,
            loadMethod = null,
            rpe = null,
            note = null,
            primarySource = ActivitySource.FIT_IMPORT,
            mergedSources = listOf(ActivitySource.FIT_IMPORT),
            dedupeBucket = "$group|${start / DEDUPE_BUCKET_MILLIS}",
            userEditedFields = emptyList(),
            hasStreams = streams != null,
            streams = streams,
            laps = toLaps(laps),
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )
    }

    /**
     * The shared time axis of §2.2.2: whole-second offsets from the session start, at most one
     * sample per second (the first of each second wins).
     *
     * Per-sample gaps are preserved: a record without a heart rate contributes `null` to [hr],
     * exactly as the domain models a strap dropout. The purely numeric channels are
     * `DoubleArray`s, which cannot carry a `null`, so a gap there is filled last-value-carried-
     * forward (a leading gap takes the first known value) and the channel is omitted entirely
     * when no record carried it.
     */
    private fun toStreams(startMillis: Long, records: List<FitRecord>): ActivityStreams? {
        val kept = mutableListOf<Pair<Int, FitRecord>>()
        var lastSecond = Int.MIN_VALUE
        for (record in records) {
            val second = ((record.timestampMillis - startMillis) / 1000L).toInt()
            if (second >= 0 && second > lastSecond) {
                kept += second to record
                lastSecond = second
            }
        }
        if (kept.isEmpty()) return null

        val offsets = kept.map { it.first }
        val rows = kept.map { it.second }
        val positions = rows.map { row ->
            val lat = row.positionLatSemicircles
            val lng = row.positionLongSemicircles
            if (lat == null || lng == null) null else GeoPoint(Semicircles.toE7(lat), Semicircles.toE7(lng))
        }
        return ActivityStreams(
            sampleOffsetsSec = offsets.toIntArray(),
            hr = rows.map { it.hr },
            distanceMeters = rows.map { it.distanceMeters }.carryForward(),
            speedMps = rows.map { it.speedMps }.carryForward(),
            cadenceSpm = rows.map { it.cadenceSpm?.toDouble() }.carryForward(),
            altitudeM = rows.map { it.altitudeM }.carryForward(),
            latLngE7 = positions.carryForwardPositions(),
            powerW = rows.map { it.powerW?.toDouble() }.carryForward()
                ?.map { it.roundHalfUp() }
                ?.toIntArray(),
            sampleCount = offsets.size,
            medianIntervalSec = offsets.medianInterval(),
        )
    }

    private fun toLaps(laps: List<FitLap>): List<Lap> = laps
        .sortedWith(compareBy({ it.startAtMillis }, { it.messageIndex ?: 0 }))
        .mapIndexed { index, lap ->
            Lap(
                id = 0L,
                activityId = 0L,
                lapIndex = index,
                startAtMillis = lap.startAtMillis,
                durationSec = (lap.totalTimerSec ?: lap.totalElapsedSec)?.roundHalfUp() ?: 0,
                distanceMeters = lap.totalDistanceMeters,
                avgHr = lap.avgHr,
                maxHr = lap.maxHr,
                avgSpeedMps = lap.avgSpeedMps,
                energyKcal = lap.totalCalories?.toDouble(),
            )
        }

    /** Session end: elapsed time when the file states it, else the last record, else the start. */
    private fun endOf(session: FitSession, records: List<FitRecord>): Long {
        val seconds = session.totalElapsedSec ?: session.totalTimerSec
        if (seconds != null) return session.startAtMillis + (seconds * 1000.0).toLong()
        val last = records.filter { it.timestampMillis >= session.startAtMillis }
            .maxOfOrNull { it.timestampMillis }
        return last ?: session.startAtMillis
    }

    private fun fallbackSpeed(distanceMeters: Double?, durationSec: Int): Double? =
        if (distanceMeters == null || durationSec <= 0) null else distanceMeters / durationSec

    /**
     * FIT records running cadence per leg (strides/min); the domain — like Health Connect — stores
     * steps per minute, so running cadences are doubled and every other sport is left alone. A
     * ride's cadence is already revolutions per minute and passes through untouched (P12).
     */
    private fun toStepsPerMinute(cadence: Double, group: SportGroup): Double =
        if (group == SportGroup.RUN || group == SportGroup.WALK) cadence * 2.0 else cadence

    private companion object {
        /** 5-minute de-dup buckets (§2.4). */
        const val DEDUPE_BUCKET_MILLIS = 300_000L
        const val EXTERNAL_ID_LENGTH = 32
    }
}

// ---- helpers ------------------------------------------------------------------------------

/** SHA-256 as lowercase hex — the `externalId`/file-hash primitive of §2.4 and P7.5. */
internal object Sha256 {

    fun hex(input: String): String = input.encodeUtf8().sha256().hex()

    fun hex(bytes: ByteArray): String = bytes.toByteString().sha256().hex()
}

private fun List<Double?>.carryForward(): DoubleArray? {
    val first = firstOrNull { it != null } ?: return null
    var current = first
    return DoubleArray(size) { index ->
        this[index]?.also { current = it } ?: current
    }
}

private fun List<GeoPoint?>.carryForwardPositions(): List<GeoPoint>? {
    var current = firstOrNull { it != null } ?: return null
    return map { point -> point?.also { current = it } ?: current }
}

/** Median gap between consecutive offsets; `0.0` for a single sample (mirrors `HcMapper`). */
private fun List<Int>.medianInterval(): Double {
    if (size < 2) return 0.0
    val gaps = (1 until size).map { (this[it] - this[it - 1]).toDouble() }.sorted()
    val middle = gaps.size / 2
    return if (gaps.size % 2 == 1) gaps[middle] else (gaps[middle - 1] + gaps[middle]) / 2.0
}

/** Half-up rounding (§3 preamble, amendment A4) — `kotlin.math.round` is half-to-even. */
private fun Double.roundHalfUp(): Int = floor(this + 0.5).toInt()
