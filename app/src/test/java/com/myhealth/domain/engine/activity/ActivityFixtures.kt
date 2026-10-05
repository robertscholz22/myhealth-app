package com.myhealth.domain.engine.activity

import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.Lap
import com.myhealth.domain.model.SportType
import com.myhealth.testutil.Fixtures

/** Shared builders for the de-dup/merge tests (PLAN §2.4, P2.4). */
internal object ActivityFixtures {

    const val NOW: Long = 1_757_000_000_000L

    /**
     * A single-source candidate session as the Health Connect / FIT mappers produce it:
     * `id = 0`, `mergedSources = [source]`, no user edits, bucket derived from the start.
     */
    fun session(
        source: ActivitySource,
        startIso: String = "2026-09-12T06:00:00Z",
        durationSec: Int = 3600,
        sportType: SportType = SportType.RUN_OUTDOOR,
        distanceMeters: Double? = 10_000.0,
        title: String? = null,
        activeEnergyKcal: Double? = null,
        totalEnergyKcal: Double? = null,
        avgHr: Int? = null,
        maxHr: Int? = null,
        avgSpeedMps: Double? = null,
        elevationGainM: Double? = null,
        avgPowerW: Int? = null,
        maxPowerW: Int? = null,
        normalizedPowerW: Int? = null,
        note: String? = null,
        rpe: Int? = null,
        trimp: Double? = null,
        streams: ActivityStreams? = null,
        laps: List<Lap> = emptyList(),
        id: Long = 0L,
        createdAtMillis: Long = NOW,
    ): ActivitySession {
        val start = Fixtures.millis(startIso)
        return ActivitySession(
            id = id,
            startAtMillis = start,
            endAtMillis = start + durationSec * 1000L,
            day = Fixtures.epochDay(startIso.substringBefore('T')),
            sportType = sportType,
            sportGroup = sportType.group,
            title = title,
            durationSec = durationSec,
            elapsedSec = durationSec,
            distanceMeters = distanceMeters,
            activeEnergyKcal = activeEnergyKcal,
            totalEnergyKcal = totalEnergyKcal,
            avgHr = avgHr,
            maxHr = maxHr,
            avgSpeedMps = avgSpeedMps,
            maxSpeedMps = null,
            avgCadenceSpm = null,
            elevationGainM = elevationGainM,
            avgPowerW = avgPowerW,
            maxPowerW = maxPowerW,
            normalizedPowerW = normalizedPowerW,
            trimp = trimp,
            loadMethod = null,
            rpe = rpe,
            note = note,
            primarySource = source,
            mergedSources = listOf(source),
            dedupeBucket = DedupeKey.of(sportType.group, start),
            userEditedFields = emptyList(),
            hasStreams = streams != null,
            streams = streams,
            laps = laps,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = createdAtMillis,
        )
    }

    fun streams(vararg hr: Int): ActivityStreams = ActivityStreams(
        sampleOffsetsSec = IntArray(hr.size) { it },
        hr = hr.toList(),
        sampleCount = hr.size,
        medianIntervalSec = 1.0,
    )

    fun lap(index: Int, distanceMeters: Double): Lap = Lap(
        id = 0,
        activityId = 0,
        lapIndex = index,
        startAtMillis = NOW,
        durationSec = 300,
        distanceMeters = distanceMeters,
        avgHr = null,
        maxHr = null,
        avgSpeedMps = null,
        energyKcal = null,
    )
}
