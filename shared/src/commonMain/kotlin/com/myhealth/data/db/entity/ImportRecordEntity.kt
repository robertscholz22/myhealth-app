package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.ImportKind
import kotlinx.serialization.Serializable

/**
 * `import_record` (PLAN §2.2.6) — audit row per imported file. [fileHashSha256] is uniquely
 * indexed, so re-importing the same file is detected before any parsing work happens.
 */
@Serializable
@Entity(
    tableName = "import_record",
    indices = [
        Index(value = ["fileHashSha256"], unique = true, name = "uq_import_hash"),
        Index(value = ["importedAtMillis"], name = "idx_import_at"),
    ],
)
data class ImportRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val kind: ImportKind,
    val fileName: String,
    val fileHashSha256: String,
    val importedAtMillis: Long,
    val itemsParsed: Int = 0,
    val itemsInserted: Int = 0,
    val itemsDuplicate: Int = 0,
    val errorsJson: String? = null,
)
