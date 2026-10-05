package com.myhealth.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.Sex
import kotlinx.serialization.Serializable

/**
 * Single-row `profile` table (PLAN §2.2.1). [id] is always [SINGLETON_ID]; the repository never
 * inserts a second row.
 */
@Serializable
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Long = SINGLETON_ID,
    val displayName: String,
    val sex: Sex,
    /** Epoch day. */
    val birthDay: Long,
    val heightCm: Double,
    val neatLevel: NeatLevel = NeatLevel.LIGHT_ACTIVE,
    val goalWeightKg: Double? = null,
    /** UI clamps to [-1.0, +0.5]. */
    val goalPaceKgPerWeek: Double = 0.0,
    val restingHrManual: Int? = null,
    val maxHrManual: Int? = null,
    val fallbackWeightKg: Double? = null,
    val sleepTargetHours: Double = 8.0,
    /** JSON `{"RUN":3,"STRENGTH":2,"SOCCER":2}` — sessions/week caps. */
    val preferredSportsJson: String = "{}",
    val mobilityOnRestDays: Boolean = true,
    /** Manual FTP override in watts (P12, DB v5); wins over every estimate when set. */
    val ftpWattsManual: Int? = null,
    /**
     * The owner has an indoor trainer (P12, DB v5). The SQL default is declared explicitly so the
     * exported schema carries `DEFAULT 0`, matching the `ALTER TABLE … NOT NULL DEFAULT 0` that
     * `MIGRATION_4_5` has to write (SQLite cannot add a `NOT NULL` column without one).
     */
    @ColumnInfo(defaultValue = "0")
    val indoorTrainerAvailable: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** Manual zone override as `[z2,z3,z4,z5]` bpm (P14, DB v6; §3.9). Nullable, so the
     * migration adds it with a plain `ALTER TABLE … ADD COLUMN`. */
    val hrZoneBoundsJson: String? = null,
    /** Lactate-threshold HR anchoring the Friel scheme (P14, DB v6; §3.9). */
    val lactateThresholdHrManual: Int? = null,
    /**
     * "My equipment" as a JSON array of `Equipment` names (P16, DB v7); `null` means everything.
     * Nullable, so the migration adds it with a plain `ALTER TABLE … ADD COLUMN`.
     */
    val availableEquipmentJson: String? = null,
) {
    companion object {
        const val SINGLETON_ID: Long = 1L
    }
}
