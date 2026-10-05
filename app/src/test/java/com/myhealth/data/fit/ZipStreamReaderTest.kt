package com.myhealth.data.fit

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import okio.BufferedSource
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * P20.2: the common [ZipStreamReader] must list the same entries with the same bytes as
 * `java.util.zip.ZipInputStream`, which the archive import used until then, and fail where it
 * failed. Nested archives are compared recursively.
 *
 * `zip05` reads real archives when `ZIP_CORPUS` names them (paths separated by `:`, e.g. the
 * owner's Garmin export, kept outside the repository); without it the test is skipped.
 */
class ZipStreamReaderTest {

    /** `path → sha256` of every file entry, nested archives expanded, as ZipInputStream sees it. */
    private fun javaListing(input: InputStream, prefix: String = ""): List<String> {
        val zip = ZipInputStream(input)
        val out = mutableListOf<String>()
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            if (entry.name.endsWith(".zip", ignoreCase = true)) {
                out += javaListing(object : java.io.FilterInputStream(zip) {
                    override fun close() = Unit
                }, "$prefix${entry.name}!/")
            } else {
                out += "$prefix${entry.name} ${sha(zip)}"
            }
        }
        return out
    }

    private fun commonListing(
        source: BufferedSource,
        inflaters: () -> RawInflater,
        prefix: String = "",
    ): List<String> {
        val zip = ZipStreamReader(source, inflaters)
        val out = mutableListOf<String>()
        while (true) {
            val entry = zip.nextEntry() ?: break
            if (entry.isDirectory) continue
            if (entry.name.endsWith(".zip", ignoreCase = true)) {
                out += commonListing(entry.data, inflaters, "$prefix${entry.name}!/")
            } else {
                out += "$prefix${entry.name} ${sha(entry.data)}"
            }
        }
        return out
    }

    /** Streamed, so multi-gigabyte entries (a Health export's `export.xml`) fit in the test heap. */
    private fun sha(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha(source: BufferedSource): String {
        val hashing = HashingSink.sha256(blackholeSink())
        source.readAll(hashing)
        return hashing.hash.hex()
    }

    /**
     * ZipInputStream's listing and the common reader's (or "fail" for either). The common reader
     * runs with both inflaters — zlib (Android) and [KotlinRawInflater] (iOS) — which must agree.
     */
    private fun both(bytes: ByteArray): Pair<Any, Any> {
        val java = runCatching { javaListing(bytes.inputStream()) }.fold({ it }, { "fail" })
        val zlib = runCatching { commonListing(Buffer().write(bytes), ::JavaRawInflater) }.fold({ it }, { "fail" })
        val kotlin = runCatching { commonListing(Buffer().write(bytes), ::KotlinRawInflater) }.fold({ it }, { "fail" })
        assertThat(kotlin).isEqualTo(zlib)
        return java to zlib
    }

    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Set<String> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                if (name in stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val random = Random(2)
    private fun noise(size: Int) = ByteArray(size) { random.nextInt(256).toByte() }
    private fun text(size: Int) = ByteArray(size) { "abcdefgh,;\n0123"[random.nextInt(15)].code.toByte() }

    @Test
    fun zip01_stored_deflated_empty_and_directory_entries_match() {
        val bytes = zip(
            "DI_CONNECT/" to ByteArray(0),
            "DI_CONNECT/a.fit" to noise(70_000),
            "DI_CONNECT/b.csv" to text(250_000),
            "empty.csv" to ByteArray(0),
            "stored.fit" to noise(5_000),
            "Größe/äöü.csv" to text(10),
            stored = setOf("stored.fit"),
        )
        val (java, common) = both(bytes)
        assertThat(common).isEqualTo(java)
        assertThat(java as List<*>).hasSize(5)
    }

    @Test
    fun zip02_nested_archives_match_and_the_outer_walk_continues_after_them() {
        val inner = zip("x.fit" to noise(30_000), "deeper.zip" to zip("y.csv" to text(9_000)))
        val bytes = zip(
            "first.csv" to text(100),
            "uploads.zip" to inner,
            "stored.zip" to inner,
            "last.fit" to noise(1_000),
            stored = setOf("stored.zip"),
        )
        val (java, common) = both(bytes)
        assertThat(common).isEqualTo(java)
        assertThat((java as List<*>).map { it.toString().substringBefore(' ') }).containsExactly(
            "first.csv", "uploads.zip!/x.fit", "uploads.zip!/deeper.zip!/y.csv",
            "stored.zip!/x.fit", "stored.zip!/deeper.zip!/y.csv", "last.fit",
        ).inOrder()
    }

    @Test
    fun zip03_not_a_zip_or_empty_yields_nothing_on_both_sides() {
        listOf(ByteArray(0), text(500), "PK".toByteArray()).forEach {
            val (java, common) = both(it)
            assertThat(common).isEqualTo(java)
            assertThat(common).isEqualTo(emptyList<String>())
        }
    }

    @Test
    fun zip04_corruption_and_truncation_fail_like_zip_input_stream() {
        val good = zip("a.fit" to noise(40_000), "b.csv" to text(40_000), "s.fit" to noise(3_000), stored = setOf("s.fit"))
        val cases = buildList {
            add(good.copyOf(good.size / 3))
            add(good.copyOf(good.size - 300))
            // Flip a byte in the stored entry's data: only the CRC check can notice.
            val storedAt = String(good, Charsets.ISO_8859_1).indexOf("s.fit") + 5
            add(good.copyOf().also { it[storedAt + 100] = (it[storedAt + 100].toInt() xor 1).toByte() })
            repeat(40) { add(good.copyOf().also { b -> val i = random.nextInt(30, b.size); b[i] = (b[i] + 1).toByte() }) }
        }
        cases.forEachIndexed { index, bytes ->
            val (java, common) = both(bytes)
            assertThat(common.toString().take(300)).isEqualTo(java.toString().take(300))
            if (index < 3) assertThat(common).isEqualTo("fail")
        }
    }

    @Test
    fun zip05_real_archives_match() {
        val paths = System.getenv("ZIP_CORPUS")?.split(':')?.map(::File)?.filter { it.isFile }.orEmpty()
        assumeTrue("ZIP_CORPUS not set", paths.isNotEmpty())
        for (file in paths) {
            val java = file.inputStream().buffered().use { javaListing(it) }
            for (inflater in listOf(::JavaRawInflater, ::KotlinRawInflater)) {
                val started = System.nanoTime()
                val common = file.source().buffer().use { commonListing(it, inflater) }
                val ms = (System.nanoTime() - started) / 1_000_000
                println("ZIP corpus ${file.name}: ${java.size} files, ${inflater.name} ${common.size} in $ms ms")
                assertThat(common).isEqualTo(java)
            }
        }
    }
}
