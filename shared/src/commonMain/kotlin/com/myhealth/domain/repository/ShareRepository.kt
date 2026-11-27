package com.myhealth.domain.repository

import com.myhealth.domain.share.LocalShareState
import com.myhealth.domain.share.ShareCode
import com.myhealth.domain.share.ShareConnection
import com.myhealth.domain.share.ShareLink
import com.myhealth.domain.share.ShareMember
import com.myhealth.domain.share.SharePlanner
import com.myhealth.domain.share.ShareRef
import com.myhealth.domain.share.SharedItem
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/** P27: the family library this device joined. */
data class ShareSetup(
    val projectId: String,
    val apiKey: String,
    val joinCode: String,
    /** This device's author id (anonymous Firebase uid). */
    val uid: String,
    val displayName: String,
) {
    /** The connection code to send to family members. */
    val code: String get() = ShareCode.encode(ShareConnection(projectId, apiKey, joinCode))
}

/** The library as last fetched. */
data class LibrarySnapshot(val items: List<SharedItem>, val members: List<ShareMember>, val fetchedAtMillis: Long)

/** What an import or "Update all" did. */
data class ImportResult(val imported: Int, val updated: Int, val problems: List<SharePlanner.ImportProblem>)

/**
 * P27: sharing own content through a family library. Every write that touches local content keeps
 * the [SharePlanner] invariant: a pull never writes to a row that is mine.
 */
interface ShareRepository {

    val setup: Flow<ShareSetup?>

    val links: Flow<List<ShareLink>>

    /** The last fetched library (null until the first successful [refresh]). */
    val library: Flow<LibrarySnapshot?>

    /** Signs in (or reuses this device's sign-in), registers as a member and stores the connection. */
    suspend fun connect(connection: ShareConnection, displayName: String): Outcome<Unit>

    suspend fun rename(displayName: String): Outcome<Unit>

    /**
     * Forgets the connection. Local content stays; my rows lose their library link (the copies in
     * the library stay), imported rows stay credited to their authors.
     */
    suspend fun leave(): Outcome<Unit>

    suspend fun refresh(): Outcome<LibrarySnapshot>

    /** Uploads [ref] plus my rows it needs. Returns how many items were uploaded. */
    suspend fun share(ref: ShareRef): Outcome<Int>

    /** My shared rows whose content changed since their upload. */
    suspend fun changedMine(): List<ShareRef>

    suspend fun pushChanges(): Outcome<Int>

    /** Removes my item from the library; the local row stays. */
    suspend fun stopSharing(ref: ShareRef): Outcome<Unit>

    /** Removes one of my items from the library by its id (e.g. its local row was deleted). */
    suspend fun deleteRemote(shareId: String): Outcome<Unit>

    suspend fun import(shareIds: List<String>): Outcome<ImportResult>

    suspend fun updateAll(): Outcome<ImportResult>

    /** "Make my copy" of a row that is theirs: a new local row of mine, crediting the original. */
    suspend fun fork(ref: ShareRef): Outcome<ShareRef>

    suspend fun stateOf(ref: ShareRef): LocalShareState
}
