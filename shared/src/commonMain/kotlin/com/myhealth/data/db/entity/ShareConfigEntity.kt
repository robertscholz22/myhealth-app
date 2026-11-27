package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * `share_config` (P27, DB v13) — the single row (`id = 1`) describing the family library this
 * device joined: the Firebase project, the join code embedded in its rules, and this device's
 * anonymous sign-in. It is in the JSON backup, so a restored phone keeps editing its own items.
 */
@Serializable
@Entity(tableName = "share_config")
data class ShareConfigEntity(
    @PrimaryKey val id: Int = 1,
    val projectId: String,
    val apiKey: String,
    val joinCode: String,
    /** Anonymous Firebase uid: this device's author id. */
    val uid: String,
    val refreshToken: String,
    val displayName: String,
    val joinedAtMillis: Long,
)
