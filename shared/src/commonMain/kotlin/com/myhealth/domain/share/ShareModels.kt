package com.myhealth.domain.share

/** P27: the four kinds of own content that can go into a family library. */
enum class ShareKind { EXERCISE, INGREDIENT, MEAL, WORKOUT }

/** A local content row: its kind plus its primary key as text (`USER_…` or the numeric id). */
data class ShareRef(val kind: ShareKind, val localKey: String)

/**
 * Mirrors `share_link`. A row with an [authorId] other than this device's is **theirs**:
 * read-only, changed only by a pull of the same [shareId].
 */
data class ShareLink(
    val kind: ShareKind,
    val localKey: String,
    val shareId: String? = null,
    val authorId: String? = null,
    val authorName: String? = null,
    val version: Long? = null,
    val sharedAtMillis: Long? = null,
    val remoteGone: Boolean = false,
    val basedOnShareId: String? = null,
    val basedOnName: String? = null,
    val basedOnAuthor: String? = null,
) {
    val ref: ShareRef get() = ShareRef(kind, localKey)

    /** Someone else's item, imported from the library. `me == null` (never joined): every author is someone else. */
    fun isTheirs(me: String?): Boolean = shareId != null && authorId != null && authorId != me

    /** One of my items that is in the library. */
    fun isMineShared(me: String?): Boolean = shareId != null && authorId != null && authorId == me
}

/** One document of the library's `items` collection. */
data class SharedItem(
    val shareId: String,
    val kind: ShareKind,
    val ownerUid: String,
    val authorName: String,
    val name: String,
    val version: Long,
    val updatedAtMillis: Long,
    val schema: Int,
    /** JSON of the kind's payload ([SharePayloads]). */
    val payload: String,
)

/** One document of the library's `members` collection. */
data class ShareMember(val uid: String, val name: String, val joinedAtMillis: Long)

/** What the library says about one remote item, seen from this device. */
enum class RemoteStatus {
    /** I am its author. */
    YOURS,

    /** Not on this device yet. */
    NOT_IMPORTED,

    /** Imported, and the local copy is the current version. */
    UP_TO_DATE,

    /** Imported, and the author has published a newer version. */
    UPDATE_AVAILABLE,
}

/** How one local row stands, for the chips on the content screens. */
sealed interface LocalShareState {
    /** Never shared (and not a copy of anything). */
    data object Private : LocalShareState

    /** My item, in the library; [changed] = edited since the last upload. */
    data class Shared(val changed: Boolean) : LocalShareState

    /** Someone else's item; [gone] = they stopped sharing it. */
    data class Theirs(val authorName: String, val gone: Boolean) : LocalShareState

    /** My copy of someone else's item (maybe shared again since: then also [shared]). */
    data class Fork(val basedOnName: String, val basedOnAuthor: String, val shared: Shared?) : LocalShareState
}
