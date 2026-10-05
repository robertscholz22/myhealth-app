package com.myhealth.domain.repository

import com.myhealth.domain.model.CycleEntry
import com.myhealth.domain.model.CycleForecast
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `cycle_entry` plus the forecasts [com.myhealth.domain.engine.cycle.CycleEngine] derives from it
 * (PLAN §5 P11.1).
 *
 * [isTrackingEnabled] is the one gate every caller must respect: the suggestion and nutrition
 * engines only see cycle data while it is `true`. It is `settings.cycleTrackingEnabled` **or**
 * `profile.sex == FEMALE`, so the feature is on by default for the users it was requested for and
 * `MALE`/`OTHER` can still opt in from Settings.
 */
interface CycleRepository {

    fun observeAll(): Flow<List<CycleEntry>>

    /** Where [today] sits in the cycle; `null` until a first period start is logged. */
    fun observeStatus(today: Long): Flow<CycleStatus?>

    /** The next six predicted cycles from [today]. */
    fun observeForecast(today: Long): Flow<CycleForecast>

    fun isTrackingEnabled(): Flow<Boolean>

    suspend fun getAll(): List<CycleEntry>

    suspend fun getById(id: Long): CycleEntry?

    /** One-shot [observeStatus] for the engines, which read rather than observe. */
    suspend fun statusFor(day: Long): CycleStatus?

    /** Statuses over the half-open day range `[fromDay, toDayExclusive)`, days without one omitted. */
    suspend fun statusesFor(fromDay: Long, toDayExclusive: Long): Map<Long, CycleStatus>

    suspend fun upsert(entry: CycleEntry): Outcome<Long>

    suspend fun delete(id: Long): Outcome<Unit>
}
