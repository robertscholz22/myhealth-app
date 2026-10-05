package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.RideBestKind
import kotlinx.serialization.Serializable

/**
 * `ride_best` (PLAN §2.2.6, P12) — the cycling counterpart of `running_best`.
 *
 * Like `running_best`, **all** qualifying efforts are kept and "the PR" is derived per kind:
 * `MAX(value)` for the `POWER_*` kinds (watts) and `MIN(value)` for the `TIME_*` kinds (seconds)
 * — see `RideBestDao.observeBestPerKind`. `uq_ride_best_activity_kind` prevents the same ride
 * contributing twice for one kind, which is what makes a refresh idempotent.
 *
 * The activity link is `SET_NULL`: deleting a ride must not erase the fact that the effort
 * happened, it only loses the tap-through target.
 */
@Serializable
@Entity(
    tableName = "ride_best",
    foreignKeys = [
        ForeignKey(
            entity = ActivitySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["kind", "value"], name = "idx_ride_best_kind_value"),
        Index(value = ["activityId", "kind"], unique = true, name = "uq_ride_best_activity_kind"),
    ],
)
data class RideBestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val kind: RideBestKind,
    /** Watts for a `POWER_*` kind, seconds for a `TIME_*` kind. */
    val value: Double,
    val activityId: Long? = null,
    val day: Long,
    /** True when derived from sparse samples or scaled from the whole ride. */
    val isEstimated: Boolean = false,
    val createdAtMillis: Long,
)
