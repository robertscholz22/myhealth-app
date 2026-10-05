package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** `water_log` (PLAN §2.2.5) — one drink entry; the day's intake is `SUM(ml)`. */
@Serializable
@Entity(
    tableName = "water_log",
    indices = [Index(value = ["day"], name = "idx_water_day")],
)
data class WaterLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val day: Long,
    val atMinuteOfDay: Int? = null,
    val ml: Int,
)
