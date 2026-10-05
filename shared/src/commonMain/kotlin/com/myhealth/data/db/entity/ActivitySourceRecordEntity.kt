package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.ActivitySource
import kotlinx.serialization.Serializable

/**
 * `activity_source_record` (PLAN §2.2.2) — one row per arrival from each source; the merger reads
 * these to build the canonical `activity_session`.
 *
 * `uq_asr (source, externalId)` is **the idempotency key for sync and import**: re-running either
 * never creates duplicates. The link to the canonical row is soft (`SET_NULL`) so deleting a
 * merged activity keeps the raw arrivals for a later re-merge.
 */
@Serializable
@Entity(
    tableName = "activity_source_record",
    foreignKeys = [
        ForeignKey(
            entity = ActivitySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["activityId"], name = "idx_asr_activity"),
        Index(value = ["source", "externalId"], unique = true, name = "uq_asr"),
        Index(value = ["importRecordId"], name = "idx_asr_import"),
    ],
)
data class ActivitySourceRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val activityId: Long? = null,
    val source: ActivitySource,
    /** HC `metadata.id`, FIT `file_id` hash, CSV row hash, Garmin `activityId` (§2.4). */
    val externalId: String,
    /** Normalized snapshot of the source's fields. */
    val payloadJson: String,
    val receivedAtMillis: Long,
    /**
     * The `import_record` that wrote this row, or `null` for a sync arrival (DB v4). It is what
     * "Undo import" selects on; the link stays soft (no foreign key) because deleting the audit
     * row must not cascade into the raw arrivals.
     */
    val importRecordId: Long? = null,
)
