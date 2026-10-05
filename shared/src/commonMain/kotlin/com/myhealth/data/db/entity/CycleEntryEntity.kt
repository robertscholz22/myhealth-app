package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * `cycle_entry` (PLAN §5 P11.1): one logged period, anchored on its first day.
 *
 * [periodStartDay] carries a **unique** index — a period starts on exactly one day, so logging the
 * same start twice updates the existing row instead of creating a second cycle that would corrupt
 * every interval the engine averages.
 */
@Serializable
@Entity(
    tableName = "cycle_entry",
    indices = [Index(value = ["periodStartDay"], unique = true, name = "uq_cycle_entry_start")],
)
data class CycleEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Epoch day of the first day of the period (§2.2 day convention). */
    val periodStartDay: Long,
    /** Inclusive epoch day of the last period day; `null` while the period is still running. */
    val periodEndDay: Long? = null,
    val note: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
