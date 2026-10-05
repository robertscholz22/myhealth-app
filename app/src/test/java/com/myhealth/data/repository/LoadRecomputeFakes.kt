package com.myhealth.data.repository

import com.myhealth.domain.model.AppSettings
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.model.ThemeMode
import com.myhealth.domain.repository.LoadRepository
import com.myhealth.domain.repository.RideBestRepository
import com.myhealth.domain.repository.RunningBestRepository
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** In-memory fakes for [LoadRecomputeTest] (PLAN P5.5) — no Room, no Android. */

internal class FakeLoadRepository : LoadRepository {

    val rows = mutableMapOf<Long, DailyLoad>()

    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<DailyLoad>> =
        flowOf(rows.values.filter { it.day in fromDay..toDay }.sortedBy { it.day })

    override fun observeLatest(): Flow<DailyLoad?> = flowOf(rows.values.maxByOrNull { it.day })

    override suspend fun getRange(fromDay: Long, toDay: Long): List<DailyLoad> =
        rows.values.filter { it.day in fromDay..toDay }.sortedBy { it.day }

    override suspend fun getLatest(): DailyLoad? = rows.values.maxByOrNull { it.day }

    /** Not exercised by [LoadRecomputeTest] — that test drives [LoadRecomputeService] directly. */
    override suspend fun recomputeFrom(fromDay: Long): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun upsertAll(days: List<DailyLoad>): Outcome<Unit> {
        days.forEach { rows[it.day] = it }
        return Outcome.Ok(Unit)
    }

    override suspend fun deleteBefore(beforeDay: Long): Outcome<Unit> {
        rows.keys.filter { it < beforeDay }.forEach { rows.remove(it) }
        return Outcome.Ok(Unit)
    }
}

internal class FakeRunningBestRepository : RunningBestRepository {

    val byActivity = mutableMapOf<Long, List<RunningBest>>()

    override fun observeBestPerDistance(): Flow<List<RunningBest>> = flowOf(
        byActivity.values.flatten()
            .groupBy { it.distanceMeters }
            .mapNotNull { (_, forDistance) -> forDistance.minByOrNull { it.timeSec } }
            .sortedBy { it.distanceMeters },
    )

    override fun observeByDistance(distanceMeters: Double, limit: Int): Flow<List<RunningBest>> = flowOf(
        byActivity.values.flatten()
            .filter { it.distanceMeters == distanceMeters }
            .sortedBy { it.timeSec }
            .take(limit),
    )

    override fun observeSince(fromDay: Long): Flow<List<RunningBest>> = flowOf(
        byActivity.values.flatten().filter { it.day >= fromDay }.sortedBy { it.day },
    )

    override suspend fun getForActivity(activityId: Long): List<RunningBest> =
        byActivity[activityId].orEmpty()

    override suspend fun replaceForActivity(activityId: Long, bests: List<RunningBest>): Outcome<Unit> {
        byActivity[activityId] = bests
        return Outcome.Ok(Unit)
    }

    override suspend fun upsertAll(bests: List<RunningBest>): Outcome<Unit> {
        bests.forEach { best ->
            val key = best.activityId ?: -1L
            byActivity[key] = byActivity[key].orEmpty() + best
        }
        return Outcome.Ok(Unit)
    }

    override suspend fun delete(id: Long): Outcome<Unit> {
        byActivity.replaceAll { _, rows -> rows.filterNot { it.id == id } }
        return Outcome.Ok(Unit)
    }
}

/** `ride_best` in memory (P12.2). [reads] records every FTP look-back read, in call order. */
internal class FakeRideBestRepository : RideBestRepository {

    val byActivity = mutableMapOf<Long, List<RideBest>>()
    val replacedActivities = mutableListOf<Long>()
    val reads = mutableListOf<Long>()

    private fun all(): List<RideBest> = byActivity.values.flatten()

    override fun observeBestPerKind(): Flow<List<RideBest>> = flowOf(
        all().groupBy { it.kind }
            .mapNotNull { (kind, rows) ->
                if (kind.isPower) rows.maxByOrNull { it.value } else rows.minByOrNull { it.value }
            }
            .sortedBy { it.kind.ordinal },
    )

    override fun observeByKind(kind: RideBestKind, limit: Int): Flow<List<RideBest>> = flowOf(
        all().filter { it.kind == kind }
            .sortedWith(if (kind.isPower) compareByDescending { it.value } else compareBy { it.value })
            .take(limit),
    )

    override suspend fun getForActivity(activityId: Long): List<RideBest> =
        byActivity[activityId].orEmpty()

    override suspend fun getSince(day: Long): List<RideBest> {
        reads += day
        return all().filter { it.day >= day }
    }

    override suspend fun replaceForActivity(activityId: Long, bests: List<RideBest>): Outcome<Unit> {
        replacedActivities += activityId
        byActivity[activityId] = bests
        return Outcome.Ok(Unit)
    }

    override suspend fun upsertAll(bests: List<RideBest>): Outcome<Unit> {
        bests.forEach { best ->
            val key = best.activityId ?: -1L
            byActivity[key] = byActivity[key].orEmpty() + best
        }
        return Outcome.Ok(Unit)
    }

    override suspend fun delete(id: Long): Outcome<Unit> {
        byActivity.replaceAll { _, rows -> rows.filterNot { it.id == id } }
        return Outcome.Ok(Unit)
    }
}

internal class FakeLoadSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {

    private val current = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = current

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        current.value = transform(current.value)
    }

    override suspend fun setSleepTargetHours(hours: Double) = update { it.copy(sleepTargetHours = hours) }
    override suspend fun setIncludeTreadmillInPrs(value: Boolean) =
        update { it.copy(includeTreadmillInPrs = value) }
    override suspend fun setMobilityOnRestDays(value: Boolean) = update { it.copy(mobilityOnRestDays = value) }
    override suspend fun setSyncIntervalHours(hours: Int) = update { it.copy(syncIntervalHours = hours) }
    override suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    override suspend fun setUseDynamicColor(value: Boolean) = update { it.copy(useDynamicColor = value) }
    override suspend fun setAllowDestructiveMigration(value: Boolean) =
        update { it.copy(allowDestructiveMigration = value) }
    override suspend fun setSuggestionHorizonDays(days: Int) = update { it.copy(suggestionHorizonDays = days) }
    override suspend fun setOffUserAgentContact(contact: String) =
        update { it.copy(offUserAgentContact = contact) }
    override suspend fun setGarminDirectEnabled(value: Boolean) = update { it.copy(garminDirectEnabled = value) }
    override suspend fun setHasCompletedOnboarding(value: Boolean) =
        update { it.copy(hasCompletedOnboarding = value) }
    override suspend fun setCycleTrackingEnabled(value: Boolean) =
        update { it.copy(cycleTrackingEnabled = value) }
}
