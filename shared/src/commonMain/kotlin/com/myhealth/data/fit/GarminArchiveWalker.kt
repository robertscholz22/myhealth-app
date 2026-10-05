package com.myhealth.data.fit

import okio.BufferedSource

/** What a yielded archive entry is: the two file kinds the import pipeline understands. */
enum class ArchiveEntryKind { FIT, CSV }

/** One file found inside a Garmin export archive, fully read into memory. */
class ArchiveEntry(
    /** Path as written in the archive, e.g. `DI_CONNECT/DI-Connect-Uploaded-Files/123.fit`. */
    val path: String,
    val kind: ArchiveEntryKind,
    val bytes: ByteArray,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** What the walk found and what it refused to look at (PLAN P7.4). */
data class ArchiveWalkResult(
    val fitCount: Int = 0,
    val csvCount: Int = 0,
    /** Entries whose normalised path escaped the archive root (zip-slip). */
    val rejectedPaths: List<String> = emptyList(),
    /** Nested archives left unopened because [GarminArchiveWalker.maxDepth] was reached. */
    val depthSkippedPaths: List<String> = emptyList(),
    /** True when the walk stopped early because the uncompressed budget ran out. */
    val truncatedBySize: Boolean = false,
    val totalUncompressedBytes: Long = 0L,
) {
    val entryCount: Int get() = fitCount + csvCount
}

/**
 * Walks a Garmin "Export Your Data" archive and yields every `.fit` and `.csv` it contains
 * (PLAN P7.4).
 *
 * The archive's own layout is deliberately not assumed: the upload folder under `DI_CONNECT/`
 * is named differently in every export vintage, so the walker just recurses — any entry ending in
 * `.zip` is opened as a nested [ZipStreamReader] in place, without buffering it (P20.2: the
 * common reader replaces `java.util.zip.ZipInputStream`).
 *
 * Guards, because this reads a file the user picked:
 * - nesting depth ≤ [maxDepth]; a deeper archive is recorded and skipped, not opened,
 * - total uncompressed bytes ≤ [maxTotalBytes], which bounds a zip bomb,
 * - an entry whose normalised path escapes the root (`../…`, an absolute path) is rejected
 *   outright — nothing here writes to disk, but a path like that is never legitimate and must not
 *   reach a file name downstream.
 *
 * Entries are handed to [walk]'s suspending callback one at a time so the caller can ingest and
 * release them; the walker itself never holds more than the current entry.
 */
class GarminArchiveWalker(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {

    suspend fun walk(input: BufferedSource, onEntry: suspend (ArchiveEntry) -> Unit): ArchiveWalkResult {
        val state = State()
        state.visit(input, depth = 1, prefix = "", onEntry = onEntry)
        return state.toResult()
    }

    private inner class State {
        var fitCount = 0
        var csvCount = 0
        var totalBytes = 0L
        var truncated = false
        val rejected = mutableListOf<String>()
        val depthSkipped = mutableListOf<String>()

        fun toResult() = ArchiveWalkResult(
            fitCount = fitCount,
            csvCount = csvCount,
            rejectedPaths = rejected.toList(),
            depthSkippedPaths = depthSkipped.toList(),
            truncatedBySize = truncated,
            totalUncompressedBytes = totalBytes,
        )

        suspend fun visit(
            input: BufferedSource,
            depth: Int,
            prefix: String,
            onEntry: suspend (ArchiveEntry) -> Unit,
        ) {
            val zip = ZipStreamReader(input)
            while (!truncated) {
                val entry = zip.nextEntry() ?: break
                val path = prefix + entry.name
                if (entry.isDirectory) continue
                if (!isSafePath(entry.name)) {
                    rejected += path
                    continue
                }
                when (kindOf(entry.name)) {
                    ArchiveEntryKind.FIT -> readEntry(entry.data, path)?.let {
                        fitCount++
                        onEntry(ArchiveEntry(path, ArchiveEntryKind.FIT, it))
                    }
                    ArchiveEntryKind.CSV -> readEntry(entry.data, path)?.let {
                        csvCount++
                        onEntry(ArchiveEntry(path, ArchiveEntryKind.CSV, it))
                    }
                    null -> if (isArchive(entry.name)) {
                        if (depth >= maxDepth) {
                            depthSkipped += path
                        } else {
                            // Recurse on the entry stream itself: the nested archive is never
                            // buffered; the next nextEntry() skips whatever the inner walk left.
                            visit(entry.data, depth + 1, "$path!/", onEntry)
                        }
                    }
                }
            }
        }

        /** Reads one entry under the remaining byte budget; `null` once the budget is spent. */
        fun readEntry(data: BufferedSource, path: String): ByteArray? {
            val out = okio.Buffer()
            while (true) {
                val read = data.read(out, COPY_BUFFER_BYTES)
                if (read <= 0) break
                totalBytes += read
                if (totalBytes > maxTotalBytes) {
                    truncated = true
                    return null
                }
            }
            return out.readByteArray().also { require(path.isNotEmpty()) }
        }
    }

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 3
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 500L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 32L * 1024

        fun kindOf(name: String): ArchiveEntryKind? = when {
            name.endsWith(".fit", ignoreCase = true) -> ArchiveEntryKind.FIT
            name.endsWith(".csv", ignoreCase = true) -> ArchiveEntryKind.CSV
            else -> null
        }

        fun isArchive(name: String): Boolean = name.endsWith(".zip", ignoreCase = true)

        /**
         * Zip-slip guard: after folding `\` to `/` and dropping `.` segments, no segment may be
         * `..` and the path may not be absolute or a Windows drive path.
         */
        fun isSafePath(name: String): Boolean {
            val normalized = name.replace('\\', '/')
            if (normalized.startsWith("/") || normalized.contains(':')) return false
            return normalized.split('/')
                .filter { it.isNotEmpty() && it != "." }
                .none { it == ".." }
        }
    }
}
