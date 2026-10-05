package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.ActivitySource
import kotlinx.serialization.Serializable

/**
 * `sleep_session` (PLAN §2.2.3).
 *
 * [night] is the epoch day the sleep is *attributed* to — the local date of [endAtMillis] — and is
 * **unique**: if two sessions map to the same night the mapper merges them (union of intervals,
 * stages concatenated) before insert, and the DAO upserts by night.
 */
@Serializable
@Entity(
    tableName = "sleep_session",
    indices = [
        Index(value = ["startAtMillis"], name = "idx_sleep_start"),
        Index(value = ["night"], unique = true, name = "idx_sleep_night"),
        Index(value = ["source", "externalId"], unique = true, name = "uq_sleep_source_ext"),
    ],
)
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val night: Long,
    val totalSleepMin: Int,
    val lightMin: Int? = null,
    val deepMin: Int? = null,
    val remMin: Int? = null,
    val awakeMin: Int? = null,
    /** `[{s,e,stage}]` JSON. */
    val stagesJson: String? = null,
    val source: ActivitySource,
    val externalId: String? = null,
    val sleepScore: Int? = null,
)
