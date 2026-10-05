package com.myhealth.data.db

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.entity.ActivitySessionEntity
import com.myhealth.data.db.entity.ImportRecordEntity
import com.myhealth.data.db.entity.IngredientEntity
import com.myhealth.data.db.entity.MealLogEntity
import com.myhealth.data.db.entity.MealLogItemEntity
import com.myhealth.data.db.entity.ProfileEntity
import com.myhealth.data.repository.RoomImportRepository
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ImportKind
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Insert/read coverage for the three representative table families of schema v1 (PLAN P1.5):
 * the single-row profile, an activity, and a meal log with its owned items.
 *
 * The migration replays live next door in `MyHealthMigrationTest` (R10: this file was past 400
 * lines once schema v6 arrived, and "does the database read and write" is a different question
 * from "does an upgrade preserve what is in it").
 *
 * Instrumented — there is no emulator on the build machine (§0.3), so this compiles in CI and is
 * only executed when a device is attached.
 */
@RunWith(AndroidJUnit4::class)
class MyHealthDatabaseTest {

    private lateinit var db: MyHealthDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyHealthDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun profile_upsert_readsBackSingletonRowWithEnumsIntact() = runTest {
        val profile = ProfileEntity(
            displayName = "Robert",
            sex = Sex.MALE,
            birthDay = 5000L,
            heightCm = 180.0,
            neatLevel = NeatLevel.ACTIVE,
            goalWeightKg = 78.0,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )
        db.profileDao().upsert(profile)

        val loaded = db.profileDao().observeProfile().first()

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.id).isEqualTo(ProfileEntity.SINGLETON_ID)
        assertThat(loaded.sex).isEqualTo(Sex.MALE)
        assertThat(loaded.neatLevel).isEqualTo(NeatLevel.ACTIVE)
        assertThat(loaded.heightCm).isEqualTo(180.0)
        assertThat(db.profileDao().count()).isEqualTo(1)
    }

    @Test
    fun activity_upsert_isVisibleByDayRangeAndDedupeBucket() = runTest {
        val start = 1_700_000_000_000L
        val id = db.activityDao().upsert(
            ActivitySessionEntity(
                startAtMillis = start,
                endAtMillis = start + 3_600_000L,
                day = 19_662L,
                sportType = SportType.RUN_OUTDOOR,
                sportGroup = SportGroup.RUN,
                title = "Morning run",
                durationSec = 3_540,
                elapsedSec = 3_600,
                distanceMeters = 10_000.0,
                avgHr = 148,
                trimp = 92.5,
                primarySource = ActivitySource.FIT_IMPORT,
                mergedSourcesCsv = "FIT_IMPORT,HEALTH_CONNECT",
                dedupeBucket = "RUN|${start / 300_000}",
                createdAtMillis = start,
                updatedAtMillis = start,
            ),
        )

        val byRange = db.activityDao().observeRange(19_660L, 19_665L).first()
        assertThat(byRange).hasSize(1)
        assertThat(byRange.single().id).isEqualTo(id)
        assertThat(byRange.single().sportType).isEqualTo(SportType.RUN_OUTDOOR)

        val byBucket = db.activityDao().getByBuckets(listOf("RUN|${start / 300_000}"))
        assertThat(byBucket.map { it.id }).containsExactly(id)

        val trimpPerDay = db.activityDao().sumTrimpPerDay(19_660L, 19_665L).first()
        assertThat(trimpPerDay).hasSize(1)
        assertThat(trimpPerDay.single().day).isEqualTo(19_662L)
        assertThat(trimpPerDay.single().trimp).isWithin(1e-9).of(92.5)
    }

    @Test
    fun mealLog_withItems_readsBackThroughRelationAndCascadesOnDelete() = runTest {
        val ingredientId = db.ingredientDao().upsert(
            IngredientEntity(
                name = "Haferflocken",
                basis = MeasureBasis.PER_100G,
                kcal = 372.0,
                proteinG = 13.5,
                carbsG = 58.7,
                fatG = 7.0,
                source = "MANUAL",
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
        )
        val logId = db.mealDao().upsert(
            MealLogEntity(
                day = 19_662L,
                atMinuteOfDay = 8 * 60,
                slot = MealSlot.BREAKFAST,
                name = "Porridge",
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
        )
        db.mealDao().upsertItems(
            listOf(
                MealLogItemEntity(
                    mealLogId = logId,
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
                ),
            ),
        )

        val day = db.mealDao().observeDay(19_662L).first()
        assertThat(day).hasSize(1)
        assertThat(day.single().log.slot).isEqualTo(MealSlot.BREAKFAST)
        assertThat(day.single().items).hasSize(1)
        assertThat(day.single().items.single().unit).isEqualTo(QuantityUnit.G)
        assertThat(day.single().items.single().kcal).isWithin(1e-9).of(297.6)

        db.mealDao().deleteById(logId)
        assertThat(db.mealDao().observeDay(19_662L).first()).isEmpty()
    }

    /**
     * BUG-19 on real SQLite: a 3 MB `errorsJson` (what ≤ 0.9.1 stored for a whole Garmin export)
     * does not fit Android's cursor window, so reading the import history crashed. The repair
     * query shrinks it without loading it, and a normal row is left alone.
     */
    @Test
    fun bug19_oversized_import_errors_are_repaired_without_reading_them() = runTest {
        val dao = db.importDao()
        dao.upsert(
            ImportRecordEntity(
                kind = ImportKind.GARMIN_ZIP, fileName = "export.zip", fileHashSha256 = "big",
                importedAtMillis = 2_000L, errorsJson = "[" + "x".repeat(3_000_000) + "]",
            ),
        )
        dao.upsert(
            ImportRecordEntity(
                kind = ImportKind.FIT_FILE, fileName = "run.fit", fileHashSha256 = "small",
                importedAtMillis = 1_000L, errorsJson = SMALL_ERRORS,
            ),
        )
        val before = runCatching { dao.observeRecent(10).first() }
        assertThat(before.exceptionOrNull()).isInstanceOf(SQLiteBlobTooBigException::class.java)

        assertThat(dao.replaceOversizedErrors(RoomImportRepository.MAX_ERRORS_JSON_CHARS, RoomImportRepository.OVERSIZED_ERRORS_JSON))
            .isEqualTo(1)

        val after = dao.observeRecent(10).first()
        assertThat(after.map { it.errorsJson })
            .containsExactly(RoomImportRepository.OVERSIZED_ERRORS_JSON, SMALL_ERRORS).inOrder()
    }

    private companion object {
        const val SMALL_ERRORS = """[{"item":"run.fit","message":"fit: no session message in the file"}]"""
    }
}
