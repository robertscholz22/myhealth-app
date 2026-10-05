package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.CalendarEventEntity
import com.myhealth.data.db.entity.EventOverrideEntity
import com.myhealth.domain.model.LinkMethod
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `calendar_event` and `event_override` (PLAN §2.2.4).
 *
 * [observeOverlapping] returns every event **whose series can touch** the window: a one-off in
 * range, or a recurring definition that started at/before `toDay` and has not ended before
 * `fromDay`. The actual occurrences are expanded in `RecurrenceExpander` (§2.2.4) — SQL cannot
 * evaluate the recurrence rule, so this query deliberately over-selects.
 */
@Dao
interface EventDao {

    @Upsert
    suspend fun upsert(entity: CalendarEventEntity): Long

    @Query("SELECT * FROM calendar_event WHERE id = :id")
    suspend fun getById(id: Long): CalendarEventEntity?

    @Query("DELETE FROM calendar_event WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "SELECT * FROM calendar_event WHERE startDay <= :toDay AND (" +
            "(recurrenceRule IS NULL AND startDay >= :fromDay) OR " +
            "(recurrenceRule IS NOT NULL AND (recurrenceUntilDay IS NULL OR recurrenceUntilDay >= :fromDay))" +
            ") ORDER BY startDay ASC, startMinuteOfDay ASC",
    )
    fun observeOverlapping(fromDay: Long, toDay: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_event WHERE isKeyEvent = 1 AND startDay >= :fromDay ORDER BY startDay ASC")
    fun observeKeyEvents(fromDay: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_event WHERE linkedActivityId = :activityId")
    suspend fun getByLinkedActivity(activityId: Long): List<CalendarEventEntity>

    @Query("UPDATE calendar_event SET linkedActivityId = :activityId, linkMethod = :linkMethod WHERE id = :id")
    suspend fun linkActivity(id: Long, activityId: Long?, linkMethod: LinkMethod?)

    // ---- overrides -----------------------------------------------------------------------------

    @Upsert
    suspend fun upsertOverride(entity: EventOverrideEntity): Long

    @Query("SELECT * FROM event_override WHERE eventId = :eventId ORDER BY occurrenceDay ASC")
    fun observeOverrides(eventId: Long): Flow<List<EventOverrideEntity>>

    @Query(
        "SELECT * FROM event_override WHERE occurrenceDay BETWEEN :fromDay AND :toDay " +
            "OR newStartDay BETWEEN :fromDay AND :toDay",
    )
    fun observeOverridesInRange(fromDay: Long, toDay: Long): Flow<List<EventOverrideEntity>>

    @Query("DELETE FROM event_override WHERE id = :id")
    suspend fun deleteOverrideById(id: Long)
}
