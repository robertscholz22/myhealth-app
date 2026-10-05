package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.ActivitySource
import kotlinx.serialization.Serializable

/**
 * `body_measurement` (PLAN §2.2.1).
 *
 * `uq_body_source_ext` is the import/sync idempotency key. SQLite treats NULLs as distinct, so
 * manually entered rows (which have no [externalId]) never collide.
 */
@Serializable
@Entity(
    tableName = "body_measurement",
    indices = [
        Index(value = ["day"], name = "idx_body_day"),
        Index(value = ["measuredAtMillis"], name = "idx_body_measured_at"),
        Index(value = ["source", "externalId"], unique = true, name = "uq_body_source_ext"),
    ],
)
data class BodyMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val measuredAtMillis: Long,
    /** Epoch day of [measuredAtMillis] in the local zone. */
    val day: Long,
    val weightKg: Double? = null,
    /** 0–100. */
    val bodyFatPercent: Double? = null,
    val muscleMassKg: Double? = null,
    val boneMassKg: Double? = null,
    val bodyWaterPercent: Double? = null,
    val source: ActivitySource,
    /** Health Connect record id, or the import row hash. */
    val externalId: String? = null,
    val note: String? = null,
)
