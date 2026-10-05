package com.myhealth.data.fit

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PLAN P7.4 over zips built programmatically in-memory — the real Garmin export is 100 MB of
 * personal data and cannot be committed as a fixture.
 */
class GarminArchiveWalkerTest {

    @Test
    fun zip01_finds_fit_in_nested_zip() = runTest {
        val inner = zipOf(
            "DI-Connect-Uploaded-Files/2026-05-10.fit" to "FIT-A".toByteArray(),
            "DI-Connect-Uploaded-Files/2026-05-11.fit" to "FIT-B".toByteArray(),
            "DI-Connect-Fitness/activities.csv" to "Activity Type,Date\n".toByteArray(),
        )
        val outer = zipOf(
            "README.txt" to "ignore me".toByteArray(),
            "DI_CONNECT/DI-Connect-Uploaded-Files.zip" to inner,
        )

        val found = mutableListOf<ArchiveEntry>()
        val result = GarminArchiveWalker().walk(Buffer().write(outer)) { found += it }

        assertThat(result.fitCount).isEqualTo(2)
        assertThat(result.csvCount).isEqualTo(1)
        assertThat(result.truncatedBySize).isFalse()
        assertThat(result.rejectedPaths).isEmpty()
        assertThat(found.map { it.name })
            .containsExactly("2026-05-10.fit", "2026-05-11.fit", "activities.csv")
        assertThat(found.first().kind).isEqualTo(ArchiveEntryKind.FIT)
        assertThat(String(found.first().bytes)).isEqualTo("FIT-A")
        // The nested archive's path is kept for the import log.
        assertThat(found.first().path).startsWith("DI_CONNECT/DI-Connect-Uploaded-Files.zip!/")
        assertThat(found.last().kind).isEqualTo(ArchiveEntryKind.CSV)
    }

    @Test
    fun zip02_depth_limit() = runTest {
        val level4 = zipOf("deep.fit" to "TOO-DEEP".toByteArray())
        val level3 = zipOf("level4.zip" to level4, "shallow.fit" to "OK".toByteArray())
        val level2 = zipOf("level3.zip" to level3)
        val level1 = zipOf("level2.zip" to level2)

        val found = mutableListOf<ArchiveEntry>()
        val result = GarminArchiveWalker(maxDepth = 3).walk(Buffer().write(level1)) { found += it }

        // Depth 1 is the outer zip, 2 is level2.zip, 3 is level3.zip; level4.zip sits one level
        // too deep and is recorded rather than opened.
        assertThat(found.map { it.name }).containsExactly("shallow.fit")
        assertThat(result.fitCount).isEqualTo(1)
        assertThat(result.depthSkippedPaths).hasSize(1)
        assertThat(result.depthSkippedPaths.single()).endsWith("level4.zip")

        // One level less nesting and the same file is reached.
        val found2 = mutableListOf<ArchiveEntry>()
        GarminArchiveWalker(maxDepth = 3).walk(Buffer().write(level2)) { found2 += it }
        assertThat(found2.map { it.name }).containsExactly("deep.fit", "shallow.fit")
    }

    @Test
    fun zip03_zip_slip_rejected() = runTest {
        val archive = zipOf(
            "../../etc/evil.fit" to "PWNED".toByteArray(),
            "/absolute/evil.fit" to "PWNED".toByteArray(),
            "ok/good.fit" to "GOOD".toByteArray(),
        )

        val found = mutableListOf<ArchiveEntry>()
        val result = GarminArchiveWalker().walk(Buffer().write(archive)) { found += it }

        assertThat(found.map { it.name }).containsExactly("good.fit")
        assertThat(result.fitCount).isEqualTo(1)
        assertThat(result.rejectedPaths).hasSize(2)
        assertThat(GarminArchiveWalker.isSafePath("a/../b.fit")).isFalse()
        assertThat(GarminArchiveWalker.isSafePath("a\\..\\b.fit")).isFalse()
        assertThat(GarminArchiveWalker.isSafePath("./DI_CONNECT/a.fit")).isTrue()
    }

    @Test
    fun zip04_size_limit() = runTest {
        val big = ByteArray(40_000) { 'x'.code.toByte() }
        val archive = zipOf(
            "a.fit" to big,
            "b.fit" to big,
            "c.fit" to big,
        )

        val found = mutableListOf<ArchiveEntry>()
        val result = GarminArchiveWalker(maxTotalBytes = 50_000L)
            .walk(Buffer().write(archive)) { found += it }

        assertThat(result.truncatedBySize).isTrue()
        assertThat(found).hasSize(1)
        assertThat(result.totalUncompressedBytes).isAtMost(50_000L + 32 * 1024L)

        // The same archive walks completely under the default budget.
        val all = mutableListOf<ArchiveEntry>()
        val full = GarminArchiveWalker().walk(Buffer().write(archive)) { all += it }
        assertThat(full.truncatedBySize).isFalse()
        assertThat(all).hasSize(3)
    }
}

/** Builds a zip in memory; entries are stored in the given order. */
internal fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}
