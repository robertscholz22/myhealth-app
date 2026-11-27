package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

/**
 * `share_link` (P27, DB v13) — how one of the owner's own content rows relates to the family's
 * shared library. Kept beside the content tables instead of as columns on them, so exercises,
 * ingredients, meal templates and workouts keep their schema and code.
 *
 * [kind] is `EXERCISE` / `INGREDIENT` / `MEAL` / `WORKOUT`; [localKey] is the row's primary key as
 * text (`USER_…` for an exercise, the numeric id otherwise).
 *
 * - **Shared or imported** rows carry [shareId] (the Firestore document id, the same on every
 *   device) plus its author. A row whose [authorId] is not this device's author is *theirs*:
 *   read-only, and only a pull of the same [shareId] may change it.
 * - **Forks** ("Make my copy") carry only the `basedOn…` columns, crediting the original.
 */
@Serializable
@Entity(
    tableName = "share_link",
    primaryKeys = ["kind", "localKey"],
    indices = [Index(value = ["shareId"], unique = true, name = "uq_share_link_share_id")],
)
data class ShareLinkEntity(
    val kind: String,
    val localKey: String,
    val shareId: String? = null,
    val authorId: String? = null,
    val authorName: String? = null,
    /** The remote version last pushed (mine) or applied (theirs). */
    val version: Long? = null,
    /** When this device last pushed or pulled the row; mine is "changed" once `updatedAt` passes it. */
    val sharedAtMillis: Long? = null,
    /** The author stopped sharing: the local copy stays, marked "No longer shared". */
    val remoteGone: Boolean = false,
    val basedOnShareId: String? = null,
    val basedOnName: String? = null,
    val basedOnAuthor: String? = null,
)
