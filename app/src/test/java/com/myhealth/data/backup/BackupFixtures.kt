package com.myhealth.data.backup

import com.myhealth.data.db.entity.ActivitySessionEntity
import com.myhealth.data.db.entity.ActivityStreamEntity
import com.myhealth.data.db.entity.BodyMeasurementEntity
import com.myhealth.data.db.entity.CycleEntryEntity
import com.myhealth.data.db.entity.DailyLoadEntity
import com.myhealth.data.db.entity.IngredientEntity
import com.myhealth.data.db.entity.MealLogEntity
import com.myhealth.data.db.entity.MealLogItemEntity
import com.myhealth.data.db.entity.ProfileEntity
import com.myhealth.data.db.entity.RideBestEntity
import com.myhealth.data.db.entity.RunningBestEntity
import com.myhealth.data.db.entity.ExerciseProgressEntity
import com.myhealth.data.db.entity.StrengthSetLogEntity
import com.myhealth.data.db.entity.StrengthWorkoutEntity
import com.myhealth.data.db.entity.StrengthWorkoutExerciseEntity
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.Feedback
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType

/**
 * A small but structurally complete dataset for the backup tests: the singleton profile, a
 * day-keyed cache row, an activity with its stream and its PR row, an ingredient, and a meal log
 * with one item — i.e. one example of every shape the importer has to handle (keyed table, root
 * with a natural key, owned child, and a foreign key that has to be remapped).
 */
internal object BackupFixtures {

    const val START: Long = 1_789_000_000_000L
    const val DAY: Long = 20_707L

    fun profile(name: String = "Robert") = ProfileEntity(
        displayName = name,
        sex = Sex.MALE,
        birthDay = 5_000L,
        heightCm = 180.0,
        neatLevel = NeatLevel.LIGHT_ACTIVE,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    fun activity(id: Long = 1L, startAtMillis: Long = START, title: String? = "Spiel") =
        ActivitySessionEntity(
            id = id,
            startAtMillis = startAtMillis,
            endAtMillis = startAtMillis + 5_400_000L,
            day = DAY,
            sportType = SportType.SOCCER_MATCH,
            sportGroup = SportGroup.SOCCER,
            title = title,
            durationSec = 5_400,
            elapsedSec = 5_700,
            avgHr = 152,
            maxHr = 188,
            trimp = 226.2,
            primarySource = ActivitySource.HEALTH_CONNECT,
            mergedSourcesCsv = "HEALTH_CONNECT",
            dedupeBucket = "SOCCER|${startAtMillis / 300_000}",
            hasStreams = true,
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )

    fun stream(activityId: Long = 1L) = ActivityStreamEntity(
        activityId = activityId,
        sampleOffsetsSecJson = "[0,1,2]",
        hrJson = "[120,130,140]",
        sampleCount = 3,
        medianIntervalSec = 1.0,
    )

    fun runningBest(id: Long = 1L, activityId: Long? = 1L, timeSec: Int = 1229) = RunningBestEntity(
        id = id,
        distanceMeters = 5000.0,
        timeSec = timeSec,
        activityId = activityId,
        day = DAY,
        method = "BEST_SPLIT",
        paceSecPerKm = timeSec / 5,
        createdAtMillis = 1L,
    )

    /** P12: one ride best; `kind|value|day` is the natural key a MERGE matches on. */
    fun rideBest(
        id: Long = 1L,
        activityId: Long? = 1L,
        kind: RideBestKind = RideBestKind.POWER_20MIN,
        value: Double = 300.0,
    ) = RideBestEntity(
        id = id,
        kind = kind,
        value = value,
        activityId = activityId,
        day = DAY,
        isEstimated = false,
        createdAtMillis = 1L,
    )

    fun body(id: Long = 1L, externalId: String? = "hc-1", weightKg: Double = 76.0) =
        BodyMeasurementEntity(
            id = id,
            measuredAtMillis = START,
            day = DAY,
            weightKg = weightKg,
            bodyFatPercent = 17.4,
            source = ActivitySource.HEALTH_CONNECT,
            externalId = externalId,
        )

    fun dailyLoad(day: Long = DAY, ctl: Double = 86.0) = DailyLoadEntity(
        day = day,
        trimp = 226.2,
        sessionCount = 1,
        atl = 65.0,
        ctl = ctl,
        acwr = 0.76,
        tsb = -21.0,
        computedAtMillis = 1L,
    )

    fun ingredient(id: Long = 1L, name: String = "Haferflocken", barcode: String? = null) =
        IngredientEntity(
            id = id,
            name = name,
            brand = "Kölln",
            barcode = barcode,
            basis = MeasureBasis.PER_100G,
            kcal = 372.0,
            proteinG = 13.5,
            carbsG = 58.7,
            fatG = 7.0,
            source = "MANUAL",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )

    fun mealLog(id: Long = 1L, day: Long = DAY) = MealLogEntity(
        id = id,
        day = day,
        atMinuteOfDay = 8 * 60,
        slot = MealSlot.BREAKFAST,
        name = "Porridge",
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    fun mealLogItem(id: Long = 1L, mealLogId: Long = 1L, ingredientId: Long? = 1L) = MealLogItemEntity(
        id = id,
        mealLogId = mealLogId,
        ingredientId = ingredientId,
        nameSnapshot = "Haferflocken",
        quantity = 80.0,
        unit = QuantityUnit.G,
        kcal = 297.6,
        proteinG = 10.8,
        carbsG = 47.0,
        sugarG = 0.8,
        fatG = 5.6,
        satFatG = 1.0,
        fiberG = 8.0,
        saltG = 0.0,
    )

    /** The whole dataset as one backup file. */
    /** P11.1: one logged period; `periodStartDay` is the natural key a MERGE matches on. */
    fun cycleEntry(id: Long = 1L, periodStartDay: Long = DAY) = CycleEntryEntity(
        id = id,
        periodStartDay = periodStartDay,
        periodEndDay = periodStartDay + 4,
        createdAtMillis = START,
        updatedAtMillis = START,
    )

    /** P14: a built-in workout; `templateId` (else the lowercased name) is the MERGE key. */
    fun strengthWorkout(id: Long = 1L, templateId: String? = "UPPER_A", name: String = "Upper A") =
        StrengthWorkoutEntity(
            id = id,
            name = name,
            kind = StrengthWorkoutKind.UPPER,
            templateId = templateId,
            isBuiltIn = templateId != null,
            createdAtMillis = START,
            updatedAtMillis = START,
        )

    /** P14: one row of [strengthWorkout] — an owned child, inserted only under a new parent. */
    fun strengthWorkoutExercise(id: Long = 1L, workoutId: Long = 1L, orderIndex: Int = 0) =
        StrengthWorkoutExerciseEntity(
            id = id,
            workoutId = workoutId,
            orderIndex = orderIndex,
            exerciseId = "BARBELL_BENCH_PRESS",
            sets = 3,
            reps = 10,
            loadKg = 60.0,
        )

    /** P14: one logged set; `completedAtMillis|exerciseId|setIndex` is the MERGE key. */
    fun strengthSetLog(id: Long = 1L, plannedSessionId: Long? = null, setIndex: Int = 1) =
        StrengthSetLogEntity(
            id = id,
            day = DAY,
            plannedSessionId = plannedSessionId,
            activityId = null,
            exerciseId = "BARBELL_BENCH_PRESS",
            setIndex = setIndex,
            reps = 10,
            loadKg = 60.0,
            rpe = 8,
            completedAtMillis = START,
        )

    /** P16.1: the progression state of one exercise; `exerciseId` is both PK and MERGE key. */
    fun exerciseProgress(exerciseId: String = "BARBELL_BENCH_PRESS") =
        ExerciseProgressEntity(
            exerciseId = exerciseId,
            loadKg = 60.0,
            reps = 8,
            lastFeedback = Feedback.HARD,
            isEstimated = false,
            updatedDay = DAY,
        )

    fun file(exportedAtMillis: Long = START, appVersion: String = "1.0") = BackupFile(
        exportedAtMillis = exportedAtMillis,
        appVersion = appVersion,
        profile = listOf(profile()),
        bodyMeasurement = listOf(body()),
        activitySession = listOf(activity()),
        activityStream = listOf(stream()),
        dailyLoad = listOf(dailyLoad()),
        ingredient = listOf(ingredient()),
        mealLog = listOf(mealLog()),
        mealLogItem = listOf(mealLogItem()),
        runningBest = listOf(runningBest()),
        rideBest = listOf(rideBest()),
        cycleEntry = listOf(cycleEntry()),
        strengthWorkout = listOf(strengthWorkout()),
        strengthWorkoutExercise = listOf(strengthWorkoutExercise()),
        strengthSetLog = listOf(strengthSetLog()),
        exerciseProgress = listOf(exerciseProgress()),
    )
}
