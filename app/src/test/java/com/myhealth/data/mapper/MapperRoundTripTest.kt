package com.myhealth.data.mapper

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.relation.ActivityWithStream
import com.myhealth.data.db.relation.MealLogWithItems
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.CycleEntry
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Ingredient
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.Lap
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.MealLog
import com.myhealth.domain.model.MealLogItem
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.model.RationaleEntry
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.SleepStage
import com.myhealth.domain.model.SleepStageInterval
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.SuggestionStatus
import org.junit.Test

/**
 * Round-trip tests for the `data/mapper` files (PLAN P1.6): build a domain object, run
 * `toEntity()` (assembling any relation the real repository would join), then `toDomain()` back,
 * and assert the result equals the original. Every mapper file is exercised at least once.
 */
class MapperRoundTripTest {

    @Test
    fun profile_roundTrips() {
        val profile = Profile(
            id = 1L,
            displayName = "Robert",
            sex = Sex.MALE,
            birthDay = 10000L,
            heightCm = 181.0,
            neatLevel = NeatLevel.ACTIVE,
            goalWeightKg = 78.0,
            goalPaceKgPerWeek = -0.25,
            restingHrManual = 48,
            maxHrManual = 190,
            fallbackWeightKg = 80.0,
            sleepTargetHours = 7.5,
            preferredSportsJson = """{"RUN":3,"STRENGTH":2,"SOCCER":2}""",
            mobilityOnRestDays = true,
            ftpWattsManual = 285,
            indoorTrainerAvailable = true,
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L,
            hrZoneBoundsJson = "[134,148,162,176]",
            lactateThresholdHrManual = 170,
        )

        assertThat(profile.toEntity().toDomain()).isEqualTo(profile)
        // The P12/P14 defaults (no override, no trainer, derived zones) survive the round trip too.
        val plain = profile.copy(
            ftpWattsManual = null,
            indoorTrainerAvailable = false,
            hrZoneBoundsJson = null,
            lactateThresholdHrManual = null,
        )
        assertThat(plain.toEntity().toDomain()).isEqualTo(plain)
    }

    @Test
    fun bodyMeasurement_roundTrips() {
        val body = BodyMeasurement(
            id = 42L,
            measuredAtMillis = 5_000L,
            day = 19_000L,
            weightKg = 79.4,
            bodyFatPercent = 15.2,
            muscleMassKg = 34.1,
            boneMassKg = 3.2,
            bodyWaterPercent = 58.0,
            source = ActivitySource.MANUAL,
            externalId = null,
            note = "morning, fasted",
        )

        assertThat(body.toEntity().toDomain()).isEqualTo(body)
    }

    @Test
    fun activitySession_withStreams_nullHrGaps_roundTrips() {
        val streams = ActivityStreams(
            sampleOffsetsSec = intArrayOf(0, 1, 2, 3, 4),
            hr = listOf(null, 132, null, 140, 141),
            distanceMeters = doubleArrayOf(0.0, 2.5, 5.1, 7.9, 10.4),
            speedMps = doubleArrayOf(2.5, 2.6, 2.6, 2.8, 2.5),
            cadenceSpm = null,
            altitudeM = null,
            latLngE7 = null,
            sampleCount = 5,
            medianIntervalSec = 1.0,
        )
        val lap = Lap(
            id = 7L,
            activityId = 1L,
            lapIndex = 0,
            startAtMillis = 100_000L,
            durationSec = 300,
            distanceMeters = 800.0,
            avgHr = 150,
            maxHr = 160,
            avgSpeedMps = 2.7,
            energyKcal = 50.0,
        )
        val session = ActivitySession(
            id = 1L,
            startAtMillis = 100_000L,
            endAtMillis = 103_000L,
            day = 1L,
            sportType = SportType.RUN_OUTDOOR,
            sportGroup = SportGroup.RUN,
            title = "Morning run",
            durationSec = 3000,
            elapsedSec = 3050,
            distanceMeters = 10_400.0,
            activeEnergyKcal = 600.0,
            totalEnergyKcal = 700.0,
            avgHr = 140,
            maxHr = 160,
            avgSpeedMps = 2.6,
            maxSpeedMps = 2.9,
            avgCadenceSpm = 172.0,
            elevationGainM = 45.0,
            trimp = 108.1,
            loadMethod = LoadMethod.HR_SAMPLES,
            rpe = 6,
            note = "felt good",
            primarySource = ActivitySource.FIT_IMPORT,
            mergedSources = listOf(ActivitySource.FIT_IMPORT, ActivitySource.HEALTH_CONNECT),
            dedupeBucket = "RUN|333",
            userEditedFields = listOf("rpe", "note"),
            hasStreams = true,
            streams = streams,
            laps = listOf(lap),
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L,
        )

        val activityEntity = session.toEntity()
        val streamEntity = session.streams!!.toEntity(activityId = session.id)
        val lapEntities = session.laps.map { it.toEntity() }
        val rebuilt = ActivityWithStream(activity = activityEntity, stream = streamEntity, laps = lapEntities).toDomain()

        assertThat(rebuilt).isEqualTo(session)
    }

    @Test
    fun sleepRecord_withStages_roundTrips() {
        val sleep = SleepRecord(
            id = 3L,
            startAtMillis = 10_000L,
            endAtMillis = 40_000L,
            night = 19_001L,
            totalSleepMin = 420,
            lightMin = 200,
            deepMin = 120,
            remMin = 90,
            awakeMin = 10,
            stages = listOf(
                SleepStageInterval(10_000L, 20_000L, SleepStage.LIGHT),
                SleepStageInterval(20_000L, 30_000L, SleepStage.DEEP),
                SleepStageInterval(30_000L, 40_000L, SleepStage.REM),
            ),
            source = ActivitySource.HEALTH_CONNECT,
            externalId = "hc-sleep-1",
            sleepScore = 82,
        )

        assertThat(sleep.toEntity().toDomain()).isEqualTo(sleep)
    }

    @Test
    fun calendarEvent_withRecurrence_roundTrips() {
        val event = CalendarEvent(
            id = 9L,
            type = EventType.SOCCER_TRAINING,
            title = "Team training",
            startDay = 19_010L,
            startMinuteOfDay = 18 * 60,
            durationMin = 90,
            location = "Sportplatz",
            sportType = SportType.SOCCER_TRAINING,
            targetDistanceMeters = null,
            isKeyEvent = false,
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TU,TH;INTERVAL=1;UNTIL=20261231",
            recurrenceUntilDay = 19_400L,
            parentEventId = null,
            linkedActivityId = null,
            linkMethod = null,
            notes = null,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )

        assertThat(event.toEntity().toDomain()).isEqualTo(event)
    }

    @Test
    fun plannedSession_roundTrips() {
        val planned = PlannedSession(
            id = 5L,
            planId = 2L,
            day = 19_020L,
            startMinuteOfDay = 7 * 60,
            sportType = SportType.RUN_OUTDOOR,
            sessionType = com.myhealth.domain.model.SessionType.TEMPO_RUN,
            intensity = Intensity.HIGH,
            targetDurationMin = 45,
            targetDistanceMeters = 8_000.0,
            targetPaceSecPerKm = 270,
            estimatedTrimp = 95.0,
            description = "Tempo run",
            rationale = "Build phase",
            status = PlannedStatus.PLANNED,
            locked = true,
            linkedActivityId = null,
            sourceSuggestionId = 11L,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_500L,
            structureJson = """{"version":1,"templateId":"RUN_1000_I","steps":[]}""",
            workoutId = 3L,
        )

        assertThat(planned.toEntity().toDomain()).isEqualTo(planned)
        // The P14 defaults (no structure, no workout) also survive the round trip.
        val plain = planned.copy(structureJson = null, workoutId = null)
        assertThat(plain.toEntity().toDomain()).isEqualTo(plain)
    }

    /** P14: the three prescription columns `suggested_session` gained in DB v6. */
    @Test
    fun suggestedSession_roundTrips() {
        val suggested = SuggestedSession(
            id = 8L,
            batchId = 3L,
            day = 19_020L,
            sportType = SportType.RUN_OUTDOOR,
            sessionType = com.myhealth.domain.model.SessionType.INTERVAL_RUN,
            intensity = Intensity.HIGH,
            targetDurationMin = 55,
            targetDistanceMeters = null,
            estimatedTrimp = 120.0,
            score = 0.82,
            rationale = listOf(RationaleEntry("INTERVAL_STRUCTURE", "5 x 1000 m at 3:54/km")),
            status = SuggestionStatus.PROPOSED,
            targetPaceSecPerKm = 234,
            structureJson = """{"version":1,"templateId":"RUN_1000_I","steps":[]}""",
            workoutTemplateId = "UPPER_A",
        )

        assertThat(suggested.toEntity().toDomain()).isEqualTo(suggested)
        // A pre-P14 suggestion (no pace, no structure, no template) round-trips unchanged.
        val plain = suggested.copy(
            targetPaceSecPerKm = null,
            structureJson = null,
            workoutTemplateId = null,
            rationale = emptyList(),
        )
        assertThat(plain.toEntity().toDomain()).isEqualTo(plain)
    }

    @Test
    fun ingredient_roundTrips() {
        val ingredient = Ingredient(
            id = 12L,
            name = "Oat flakes",
            brand = "Kölln",
            barcode = "4000417021005",
            basis = MeasureBasis.PER_100G,
            pieceGrams = null,
            servingGrams = 40.0,
            servingLabel = "4 Tbsp (40 g)",
            kcal = 372.0,
            proteinG = 13.5,
            carbsG = 58.7,
            sugarG = 0.9,
            fatG = 7.0,
            satFatG = 1.3,
            fiberG = 10.0,
            saltG = 0.01,
            sodiumG = 0.004,
            isFavorite = true,
            source = "OFF",
            offProductJson = """{"code":"4000417021005"}""",
            lastUsedAtMillis = 5_000L,
            useCount = 4,
            archived = false,
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L,
        )

        assertThat(ingredient.toEntity().toDomain()).isEqualTo(ingredient)
    }

    @Test
    fun mealLog_withItems_roundTrips() {
        val item = MealLogItem(
            id = 1L,
            mealLogId = 20L,
            ingredientId = 12L,
            nameSnapshot = "Oat flakes",
            quantity = 40.0,
            unit = QuantityUnit.G,
            kcal = 148.8,
            proteinG = 5.4,
            carbsG = 23.5,
            sugarG = 0.36,
            fatG = 2.8,
            satFatG = 0.52,
            fiberG = 4.0,
            saltG = 0.004,
        )
        val mealLog = MealLog(
            id = 20L,
            day = 19_030L,
            atMinuteOfDay = 8 * 60,
            slot = MealSlot.BREAKFAST,
            name = "Oatmeal",
            templateId = null,
            note = null,
            items = listOf(item),
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )

        val logEntity = mealLog.toEntity()
        val itemEntities = mealLog.items.map { it.toEntity() }
        val rebuilt = MealLogWithItems(log = logEntity, items = itemEntities).toDomain()

        assertThat(rebuilt).isEqualTo(mealLog)
    }

    @Test
    fun dailyLoad_roundTrips() {
        val load = DailyLoad(
            day = 19_040L,
            trimp = 108.1,
            sessionCount = 2,
            atl = 55.3,
            ctl = 60.1,
            acwr = 1.02,
            tsb = 4.8,
            monotony = 1.3652,
            strain = 559.7,
            recoveryScore = 72,
            recoveryBand = RecoveryBand.GOOD,
            recoveryConfidence = 0.8,
            flags = listOf("MISSING_HR", "ESTIMATED_LOAD"),
            computedAtMillis = 3_000L,
        )

        assertThat(load.toEntity().toDomain()).isEqualTo(load)
    }

    @Test
    fun runningBest_roundTrips() {
        val best = RunningBest(
            id = 8L,
            distanceMeters = 5000.0,
            timeSec = 1200,
            activityId = 1L,
            day = 19_040L,
            method = "FULL_ACTIVITY",
            isEstimated = false,
            paceSecPerKm = 240,
            createdAtMillis = 3_000L,
        )

        assertThat(best.toEntity().toDomain()).isEqualTo(best)
    }

    /**
     * P12: the `powerW` channel and the three session power fields survive the JSON columns, and
     * `ActivityStreams`' hand-written `equals` really compares the new array by content.
     */
    @Test
    fun bike01_stream_power_round_trips() {
        val streams = ActivityStreams(
            sampleOffsetsSec = intArrayOf(0, 1, 2, 3),
            hr = listOf(null, null, null, null),
            cadenceSpm = doubleArrayOf(90.0, 90.0, 91.0, 90.0),
            powerW = intArrayOf(220, 221, 300, 299),
            sampleCount = 4,
            medianIntervalSec = 1.0,
        )
        val session = ActivitySession(
            id = 5L,
            startAtMillis = 200_000L,
            endAtMillis = 203_000L,
            day = 20_490L,
            sportType = SportType.CYCLING_INDOOR,
            sportGroup = SportGroup.CYCLE,
            title = "Zwift",
            durationSec = 3_600,
            elapsedSec = 3_600,
            distanceMeters = null,
            activeEnergyKcal = 886.0,
            totalEnergyKcal = null,
            avgHr = null,
            maxHr = null,
            avgSpeedMps = null,
            maxSpeedMps = null,
            // rpm for a ride, not steps per minute.
            avgCadenceSpm = 90.0,
            elevationGainM = null,
            avgPowerW = 246,
            maxPowerW = 300,
            normalizedPowerW = 255,
            trimp = null,
            loadMethod = LoadMethod.POWER_TSS,
            rpe = null,
            note = null,
            primarySource = ActivitySource.FIT_IMPORT,
            mergedSources = listOf(ActivitySource.FIT_IMPORT),
            dedupeBucket = "CYCLE|0",
            userEditedFields = emptyList(),
            hasStreams = true,
            streams = streams,
            laps = emptyList(),
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
        )

        val rebuilt = ActivityWithStream(
            activity = session.toEntity(),
            stream = streams.toEntity(activityId = session.id),
            laps = emptyList(),
        ).toDomain()

        assertThat(rebuilt).isEqualTo(session)
        assertThat(checkNotNull(rebuilt.streams).powerW).isEqualTo(intArrayOf(220, 221, 300, 299))
        // A stream without power is still a valid stream, and stays null through the round trip.
        val noPower = streams.copy(powerW = null)
        assertThat(noPower.toEntity(1L).toDomain()).isEqualTo(noPower)
        assertThat(noPower).isNotEqualTo(streams)
    }

    @Test
    fun rideBest_roundTrips() {
        val best = RideBest(
            id = 3L,
            kind = RideBestKind.POWER_20MIN,
            value = 300.0,
            activityId = 5L,
            day = 20_490L,
            isEstimated = false,
            createdAtMillis = 7_000L,
        )

        assertThat(best.toEntity().toDomain()).isEqualTo(best)
        val timeBest = best.copy(kind = RideBestKind.TIME_40K, value = 4_478.0, isEstimated = true)
        assertThat(timeBest.toEntity().toDomain()).isEqualTo(timeBest)
    }

    @Test
    fun cycleEntry_roundTrips() {
        val entry = CycleEntry(
            id = 9L,
            periodStartDay = 20_800L,
            periodEndDay = 20_804L,
            note = "heavier than usual",
            createdAtMillis = 4_000L,
            updatedAtMillis = 5_000L,
        )

        assertThat(entry.toEntity().toDomain()).isEqualTo(entry)
        // A period that has not ended yet keeps its open end through the round trip.
        val running = entry.copy(periodEndDay = null, note = null)
        assertThat(running.toEntity().toDomain()).isEqualTo(running)
        assertThat(running.periodLengthDays).isNull()
        assertThat(entry.periodLengthDays).isEqualTo(5)
    }
}
