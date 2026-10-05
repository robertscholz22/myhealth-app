package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.model.SportType
import kotlinx.serialization.Serializable

/**
 * `calendar_event` (PLAN §2.2.4) — the *definition* row. Occurrences of a recurring event are
 * expanded in `domain/engine/calendar/RecurrenceExpander.kt`, never in SQL.
 *
 * [recurrenceUntilDay] denormalizes the `UNTIL` part of [recurrenceRule] so range queries can
 * exclude finished series without parsing. The link to an activity is soft (`SET_NULL`).
 */
@Serializable
@Entity(
    tableName = "calendar_event",
    foreignKeys = [
        ForeignKey(
            entity = ActivitySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedActivityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["startDay"], name = "idx_event_start_day"),
        Index(value = ["linkedActivityId"], name = "idx_event_activity"),
        Index(value = ["parentEventId"], name = "idx_event_parent"),
    ],
)
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val type: EventType,
    val title: String,
    /** Epoch day. */
    val startDay: Long,
    /** Null = all-day. */
    val startMinuteOfDay: Int? = null,
    val durationMin: Int? = null,
    val location: String? = null,
    val sportType: SportType? = null,
    val targetDistanceMeters: Double? = null,
    /** Drives periodization: A-race / important match. */
    val isKeyEvent: Boolean = false,
    /** RFC5545 subset: `FREQ=WEEKLY;BYDAY=TU,TH;INTERVAL=1;UNTIL=yyyymmdd`. */
    val recurrenceRule: String? = null,
    val recurrenceUntilDay: Long? = null,
    /** Set on materialized overrides. */
    val parentEventId: Long? = null,
    val linkedActivityId: Long? = null,
    val linkMethod: LinkMethod? = null,
    val notes: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
