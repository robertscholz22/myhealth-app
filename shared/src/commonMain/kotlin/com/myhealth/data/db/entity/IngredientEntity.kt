package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.MeasureBasis
import kotlinx.serialization.Serializable

/**
 * `ingredient` (PLAN §2.2.5). Every nutrient value is expressed **per [basis] unit** (100 g,
 * 100 ml, or one piece); the log-time snapshot in `meal_log_item` holds absolute values.
 *
 * [barcode] is uniquely indexed where present (SQLite NULLs are distinct, so manual ingredients
 * never collide). [kcal] plus protein/carbs/fat are effectively required — validated in the
 * repository, not by the schema, so an OFF import with a gap can still be stored and fixed.
 */
@Serializable
@Entity(
    tableName = "ingredient",
    indices = [
        Index(value = ["name"], name = "idx_ingredient_name"),
        Index(value = ["barcode"], unique = true, name = "uq_ingredient_barcode"),
        Index(value = ["lastUsedAtMillis"], name = "idx_ingredient_last_used"),
    ],
)
data class IngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val basis: MeasureBasis,
    /** Mass of one piece; required when [basis] is `PER_PIECE` or pieces are usable. */
    val pieceGrams: Double? = null,
    val servingGrams: Double? = null,
    /** e.g. "1 Scheibe (30 g)". */
    val servingLabel: String? = null,
    val kcal: Double,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val sugarG: Double? = null,
    val fatG: Double? = null,
    val satFatG: Double? = null,
    val fiberG: Double? = null,
    val saltG: Double? = null,
    val sodiumG: Double? = null,
    val isFavorite: Boolean = false,
    /** `MANUAL` / `OCR` / `OFF` — provenance, not a §2.1 enum. */
    val source: String,
    /** Raw Open Food Facts payload, kept for provenance. */
    val offProductJson: String? = null,
    val lastUsedAtMillis: Long? = null,
    val useCount: Int = 0,
    val archived: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
