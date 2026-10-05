package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * `event_override` (PLAN §2.2.4) — an exception to one occurrence of a recurring event. Owned by
 * its event (`CASCADE`), at most one override per `(eventId, occurrenceDay)`.
 *
 * [action] is `SKIP`, `MOVE` or `EDIT`; it is deliberately a plain string, not an enum, because
 * it is not part of the §2.1 enum table.
 */
@Serializable
@Entity(
    tableName = "event_override",
    foreignKeys = [
        ForeignKey(
            entity = CalendarEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["eventId", "occurrenceDay"], unique = true, name = "uq_override_event_day"),
    ],
)
data class EventOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val eventId: Long,
    val occurrenceDay: Long,
    /** `SKIP` / `MOVE` / `EDIT`. */
    val action: String,
    val newStartDay: Long? = null,
    val newStartMinuteOfDay: Int? = null,
    val newDurationMin: Int? = null,
    val newTitle: String? = null,
)
