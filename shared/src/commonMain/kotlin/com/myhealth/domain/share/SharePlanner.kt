package com.myhealth.domain.share

/**
 * P27: every authorship rule of the family library, as pure functions.
 *
 * The invariant all of them protect: **a pull never writes to a row that is mine.** Mine means
 * no link, a link without an author (a fork, or a row never shared), or a link whose author is
 * this device ([me]). Only rows imported from someone else are ever replaced, and only by a
 * newer version of the very same share id.
 */
object SharePlanner {

    /** What a remote item is, seen from this device. */
    fun status(item: SharedItem, linksByShareId: Map<String, ShareLink>, me: String?): RemoteStatus {
        if (me != null && item.ownerUid == me) return RemoteStatus.YOURS
        val link = linksByShareId[item.shareId] ?: return RemoteStatus.NOT_IMPORTED
        // A link to this share id that is mine (e.g. restored from my own backup) is still mine.
        if (!link.isTheirs(me)) return RemoteStatus.YOURS
        return if ((link.version ?: 0L) >= item.version) RemoteStatus.UP_TO_DATE else RemoteStatus.UPDATE_AVAILABLE
    }

    /** How a local row stands; [updatedAtMillis] is the row's own last edit. */
    fun localState(link: ShareLink?, updatedAtMillis: Long, me: String?): LocalShareState {
        if (link == null) return LocalShareState.Private
        if (link.isTheirs(me)) {
            return LocalShareState.Theirs(link.authorName.orEmpty(), link.remoteGone)
        }
        val shared = if (link.isMineShared(me)) {
            LocalShareState.Shared(changed = updatedAtMillis > (link.sharedAtMillis ?: 0L))
        } else {
            null
        }
        if (link.basedOnShareId != null) {
            return LocalShareState.Fork(link.basedOnName.orEmpty(), link.basedOnAuthor.orEmpty(), shared)
        }
        return shared ?: LocalShareState.Private
    }

    /** True when the UI must not edit the row in place ("Make my copy" instead). */
    fun isReadOnly(link: ShareLink?, me: String?): Boolean = link?.isTheirs(me) == true

    /**
     * What to upload when I share [root]: [root] plus the dependencies that are **mine**, dependencies
     * first, each once. A dependency that is theirs is referenced by its share id and never uploaded
     * again. Sharing a row that is theirs is refused (empty list).
     */
    fun pushClosure(root: ShareRef, dependencies: (ShareRef) -> List<ShareRef>, links: Map<ShareRef, ShareLink>, me: String?): List<ShareRef> {
        if (links[root]?.isTheirs(me) == true) return emptyList()
        val out = LinkedHashSet<ShareRef>()
        fun visit(ref: ShareRef) {
            if (ref in out || links[ref]?.isTheirs(me) == true) return
            dependencies(ref).forEach(::visit)
            out += ref
        }
        visit(root)
        return out.toList()
    }

    /** One step of an import, in order. */
    sealed interface ImportStep {
        val item: SharedItem

        /** Not on the device: insert a new row (or reuse [reuseLocalKey], an ingredient with the same barcode). */
        data class Insert(override val item: SharedItem, val reuseLocalKey: String? = null) : ImportStep

        /** Theirs and outdated: replace the content of [localKey]. */
        data class Update(override val item: SharedItem, val localKey: String) : ImportStep
    }

    /** Why an import cannot go ahead. */
    sealed interface ImportProblem {
        /** [shareId] needs [missing], which is not in the library any more. */
        data class MissingDependency(val shareId: String, val missing: String) : ImportProblem
    }

    data class ImportPlan(val steps: List<ImportStep>, val problems: List<ImportProblem>)

    /**
     * Plans importing (or updating) [shareIds] plus whatever they depend on, dependencies first.
     *
     * - Mine (by owner or by link) and up-to-date items need no step; they already resolve.
     * - An ingredient with a barcode the device already has reuses that row ([barcodeOwner]), so
     *   the unique barcode index never clashes and my own ingredient stays as it is.
     * - A missing dependency is reported, and the item that needs it is left out.
     */
    fun planImport(
        shareIds: List<String>,
        remote: Map<String, SharedItem>,
        linksByShareId: Map<String, ShareLink>,
        me: String?,
        barcodeOwner: (String) -> String? = { null },
    ): ImportPlan {
        val steps = LinkedHashMap<String, ImportStep>()
        val problems = mutableListOf<ImportProblem>()
        val failed = mutableSetOf<String>()

        fun visit(id: String, stack: Set<String>): Boolean {
            if (id in steps) return true
            if (id in failed) return false
            val item = remote[id] ?: return false
            if (id in stack) return true
            val deps = SharePayloads.dependencies(item)
            var ok = true
            for (dep in deps) {
                if (remote[dep] == null) {
                    // Gone from the library, but maybe still on the device (imported earlier or mine).
                    if (linksByShareId[dep] == null) {
                        problems += ImportProblem.MissingDependency(id, dep)
                        ok = false
                    }
                } else if (!visit(dep, stack + id)) {
                    ok = false
                }
            }
            if (!ok) {
                failed += id
                return false
            }
            when (status(item, linksByShareId, me)) {
                RemoteStatus.YOURS, RemoteStatus.UP_TO_DATE -> Unit
                RemoteStatus.UPDATE_AVAILABLE ->
                    steps[id] = ImportStep.Update(item, linksByShareId.getValue(id).localKey)
                RemoteStatus.NOT_IMPORTED -> {
                    val reuse = if (item.kind == ShareKind.INGREDIENT) {
                        SharePayloads.decodeIngredient(item.payload)?.barcode?.let(barcodeOwner)
                    } else {
                        null
                    }
                    steps[id] = ImportStep.Insert(item, reuse)
                }
            }
            return true
        }

        shareIds.distinct().forEach { visit(it, emptySet()) }
        return ImportPlan(steps.values.toList(), problems)
    }

    /** Every imported item with a newer version in the library: what "Update all" imports. */
    fun updatable(remote: Collection<SharedItem>, linksByShareId: Map<String, ShareLink>, me: String?): List<String> =
        remote.filter { status(it, linksByShareId, me) == RemoteStatus.UPDATE_AVAILABLE }.map { it.shareId }

    /** Imported items whose document is gone: the author stopped sharing. Only theirs; mine are never marked. */
    fun gone(links: Collection<ShareLink>, remoteIds: Set<String>, me: String?): List<ShareLink> =
        links.filter { it.isTheirs(me) && !it.remoteGone && it.shareId !in remoteIds }

    /** My shared rows edited since their last upload: what "Upload my changes" pushes. */
    fun changedMine(links: Collection<ShareLink>, updatedAt: (ShareRef) -> Long?, me: String?): List<ShareRef> =
        links.filter { it.isMineShared(me) }
            .filter { link -> updatedAt(link.ref)?.let { it > (link.sharedAtMillis ?: 0L) } == true }
            .map { it.ref }

    /** The link for my copy ([newLocalKey]) of the theirs row [original]: credit kept, author cleared. */
    fun forkLink(original: ShareLink, originalName: String, newLocalKey: String): ShareLink = ShareLink(
        kind = original.kind,
        localKey = newLocalKey,
        basedOnShareId = original.shareId ?: original.basedOnShareId,
        basedOnName = originalName,
        basedOnAuthor = original.authorName ?: original.basedOnAuthor,
    )

    /** The link after I uploaded [ref] as [shareId] at [version]. A fork keeps its credit. */
    fun pushedLink(existing: ShareLink?, ref: ShareRef, shareId: String, me: String, myName: String, version: Long, nowMillis: Long): ShareLink =
        (existing ?: ShareLink(ref.kind, ref.localKey)).copy(
            shareId = shareId,
            authorId = me,
            authorName = myName,
            version = version,
            sharedAtMillis = nowMillis,
            remoteGone = false,
        )

    /** The link after importing [item] into [localKey]. */
    fun importedLink(item: SharedItem, localKey: String, nowMillis: Long): ShareLink = ShareLink(
        kind = item.kind,
        localKey = localKey,
        shareId = item.shareId,
        authorId = item.ownerUid,
        authorName = item.authorName,
        version = item.version,
        sharedAtMillis = nowMillis,
    )
}
