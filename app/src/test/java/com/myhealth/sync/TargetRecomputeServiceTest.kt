package com.myhealth.sync

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.WaterLog
import com.myhealth.domain.repository.NutritionRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

/** P20.2: the target recompute loop the Android worker used to run inline (PLAN P4.12). */
class TargetRecomputeServiceTest {

    private val clock = Fixtures.fixedClock("2026-09-12T12:00:00Z", java.time.ZoneId.of("UTC"))
    private val today = LocalDate.of(2026, 9, 12).toEpochDay()

    private class Repo(private val answer: (Long) -> Outcome<NutritionTarget>) : NutritionRepository {
        val days = mutableListOf<Long>()
        override suspend fun ensureTarget(day: Long): Outcome<NutritionTarget> = answer(day).also { days += day }
        override fun observeTarget(day: Long): Flow<NutritionTarget?> = emptyFlow()
        override fun observeTargets(fromDay: Long, toDay: Long): Flow<List<NutritionTarget>> = emptyFlow()
        override suspend fun getTarget(day: Long): NutritionTarget? = null
        override suspend fun upsertTarget(target: NutritionTarget): Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun deleteTarget(day: Long): Outcome<Unit> = Outcome.Ok(Unit)
        override fun observeWaterTotalMl(day: Long): Flow<Int> = emptyFlow()
        override fun observeWaterLogs(day: Long): Flow<List<WaterLog>> = emptyFlow()
        override suspend fun addWater(day: Long, ml: Int, atMinuteOfDay: Int?): Outcome<Long> = Outcome.Ok(0L)
        override suspend fun deleteWater(id: Long): Outcome<Unit> = Outcome.Ok(Unit)
    }

    @Test
    fun trs01_without_a_profile_every_day_is_skipped_without_a_retry() = runTest {
        val repo = Repo { Outcome.Err(AppError.Validation("profile", "no profile")) }
        val run = TargetRecomputeService(repo, clock).recompute()
        assertThat(repo.days).isEqualTo(((today - 1)..(today + 7)).toList())
        assertThat(run).isEqualTo(TargetRecomputeService.Result(recomputed = 0, skipped = 9, failure = null))
    }

    @Test
    fun trs02_a_storage_error_is_reported_and_the_other_days_still_run() = runTest {
        val repo = Repo { day ->
            if (day == today) Outcome.Err(AppError.Storage(IllegalStateException("disk"))) else Outcome.Err(AppError.Validation("profile", "x"))
        }
        val run = TargetRecomputeService(repo, clock).recompute()
        assertThat(repo.days).hasSize(9)
        assertThat(run.skipped).isEqualTo(8)
        assertThat(run.failure).isInstanceOf(AppError.Storage::class.java)
    }
}
