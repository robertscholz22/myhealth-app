package com.myhealth.data.fit

import com.garmin.fit.ActivityMesg
import com.garmin.fit.ActivityMesgListener
import com.garmin.fit.Decode
import com.garmin.fit.FileIdMesg
import com.garmin.fit.FileIdMesgListener
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.LapMesg
import com.garmin.fit.LapMesgListener
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionMesgListener
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import java.io.InputStream

/**
 * The Garmin-FIT-SDK decoder the app used until P20.2, kept as the oracle for the shared
 * [FitFileDecoder] (`FitDecoderOracleTest`). Unchanged apart from the name.
 *
 * Original KDoc: the only place in the app that touches the Garmin FIT SDK (PLAN P7.1).
 *
 * `Decode` pushes every message into a [MesgBroadcaster], which fans them out to the typed
 * listeners registered below; each listener copies the handful of fields the app needs into the
 * SDK-free [FitFileData]. Timestamps are converted here with [FitEpoch] so that no
 * `com.garmin.fit.DateTime` ever escapes, and positions are kept in semicircles for the mapper.
 *
 * Failure model (§1.5): a truncated or non-FIT stream raises `FitRuntimeException`, which becomes
 * [AppError.Parse] rather than propagating — one bad file inside a Garmin export ZIP must not
 * abort the whole import (P7.5).
 */
class SdkFitFileDecoder {

    fun decode(input: InputStream): Outcome<FitFileData> = try {
        Outcome.Ok(decodeOrThrow(input))
    } catch (e: FitRuntimeException) {
        Outcome.Err(AppError.Parse(what = "fit", detail = e.message ?: "not a readable FIT file"))
    } catch (e: RuntimeException) {
        Outcome.Err(AppError.Parse(what = "fit", detail = e.message ?: e.javaClass.simpleName))
    }

    private fun decodeOrThrow(input: InputStream): FitFileData {
        val collector = Collector()
        val broadcaster = MesgBroadcaster()
        broadcaster.addListener(collector as FileIdMesgListener)
        broadcaster.addListener(collector as SessionMesgListener)
        broadcaster.addListener(collector as LapMesgListener)
        broadcaster.addListener(collector as RecordMesgListener)
        broadcaster.addListener(collector as ActivityMesgListener)
        Decode().read(input, broadcaster)
        return collector.toData()
    }

    /**
     * One object implementing all five listener interfaces — the SDK dispatches on the interface
     * type, so a single instance keeps the accumulated file in one place.
     */
    private class Collector :
        FileIdMesgListener,
        SessionMesgListener,
        LapMesgListener,
        RecordMesgListener,
        ActivityMesgListener {

        private var fileId: FitFileId? = null
        private var localOffsetSec: Long? = null
        private val sessions = mutableListOf<FitSession>()
        private val laps = mutableListOf<FitLap>()
        private val records = mutableListOf<FitRecord>()

        fun toData(): FitFileData = FitFileData(
            fileId = fileId,
            sessions = sessions.sortedBy { it.startAtMillis },
            laps = laps.sortedBy { it.startAtMillis },
            records = records.sortedBy { it.timestampMillis },
            localTimestampOffsetSec = localOffsetSec,
        )

        override fun onMesg(mesg: FileIdMesg) {
            fileId = FitFileId(
                type = mesg.type?.name,
                manufacturer = mesg.manufacturer,
                product = mesg.product,
                serialNumber = mesg.serialNumber,
                timeCreatedMillis = mesg.timeCreated?.timestamp?.let(FitEpoch::toUnixMillis),
            )
        }

        override fun onMesg(mesg: SessionMesg) {
            val start = mesg.startTime?.timestamp ?: return
            sessions += FitSession(
                startAtMillis = FitEpoch.toUnixMillis(start),
                sport = mesg.sport?.name,
                subSport = mesg.subSport?.name,
                sportProfileName = mesg.sportProfileName,
                totalElapsedSec = mesg.totalElapsedTime?.toDouble(),
                totalTimerSec = mesg.totalTimerTime?.toDouble(),
                totalDistanceMeters = mesg.totalDistance?.toDouble(),
                totalCalories = mesg.totalCalories,
                avgHr = mesg.avgHeartRate?.toInt(),
                maxHr = mesg.maxHeartRate?.toInt(),
                avgSpeedMps = mesg.avgSpeed?.toDouble(),
                maxSpeedMps = mesg.maxSpeed?.toDouble(),
                avgCadenceSpm = mesg.avgCadence?.toDouble(),
                totalAscentM = mesg.totalAscent?.toDouble(),
                // `avg_power` / `max_power` / `normalized_power` are already `Integer` in the SDK.
                avgPowerW = mesg.avgPower,
                maxPowerW = mesg.maxPower,
                normalizedPowerW = mesg.normalizedPower,
            )
        }

        override fun onMesg(mesg: LapMesg) {
            val start = mesg.startTime?.timestamp ?: return
            laps += FitLap(
                messageIndex = mesg.messageIndex,
                startAtMillis = FitEpoch.toUnixMillis(start),
                totalElapsedSec = mesg.totalElapsedTime?.toDouble(),
                totalTimerSec = mesg.totalTimerTime?.toDouble(),
                totalDistanceMeters = mesg.totalDistance?.toDouble(),
                totalCalories = mesg.totalCalories,
                avgHr = mesg.avgHeartRate?.toInt(),
                maxHr = mesg.maxHeartRate?.toInt(),
                avgSpeedMps = mesg.avgSpeed?.toDouble(),
            )
        }

        override fun onMesg(mesg: RecordMesg) {
            val timestamp = mesg.timestamp?.timestamp ?: return
            records += FitRecord(
                timestampMillis = FitEpoch.toUnixMillis(timestamp),
                hr = mesg.heartRate?.toInt(),
                distanceMeters = mesg.distance?.toDouble(),
                // The "enhanced" variants are 32-bit and cover speeds/altitudes the 16-bit
                // originals overflow; devices that write both keep them in sync.
                speedMps = (mesg.enhancedSpeed ?: mesg.speed)?.toDouble(),
                cadenceSpm = mesg.cadence?.toInt(),
                altitudeM = (mesg.enhancedAltitude ?: mesg.altitude)?.toDouble(),
                powerW = mesg.power,
                positionLatSemicircles = mesg.positionLat,
                positionLongSemicircles = mesg.positionLong,
            )
        }

        override fun onMesg(mesg: ActivityMesg) {
            val local = mesg.localTimestamp ?: return
            val utc = mesg.timestamp?.timestamp ?: return
            localOffsetSec = local - utc
        }
    }
}
