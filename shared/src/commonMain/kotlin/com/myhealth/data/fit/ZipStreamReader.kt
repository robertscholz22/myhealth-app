package com.myhealth.data.fit

import kotlinx.io.IOException
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

/** A ZIP that cannot be read on (the counterpart of `java.util.zip.ZipException`). */
class ZipFormatException(message: String) : IOException(message)

/** One entry of a [ZipStreamReader]; [data] is readable until the next [ZipStreamReader.nextEntry]. */
class ZipStreamEntry internal constructor(val name: String, val data: BufferedSource) {
    val isDirectory: Boolean get() = name.endsWith("/")
}

/**
 * Reads a ZIP front to back from a stream, entry by entry, without the central directory — what
 * `java.util.zip.ZipInputStream` does, in common code (P20.2) so the Garmin archive import also
 * runs on iOS. Nested archives are read in place: an entry's [ZipStreamEntry.data] can itself be
 * handed to another reader.
 *
 * Behaviour follows `ZipInputStream`: the walk ends at the first signature that is not a local
 * file header (the central directory, or a stream that is not a ZIP at all); entries are stored or
 * deflated, any other method fails; a stored entry cannot use a data descriptor; ZIP64 sizes come
 * from the 0x0001 extra field; every entry's CRC-32 and size are checked once it has been read to
 * the end, and a mismatch fails ([ZipFormatException]). Names are read as UTF-8.
 */
class ZipStreamReader(
    private val source: BufferedSource,
    private val inflaters: () -> RawInflater = ::newRawInflater,
) {

    private var current: EntrySource? = null

    /** Skips what is left of the current entry and returns the next one, or `null` at the end. */
    fun nextEntry(): ZipStreamEntry? {
        current?.let {
            it.drain()
            current = null
        }
        if (!source.request(4) || source.buffer.getIntLe(0) != LOCAL_HEADER) return null
        source.skip(4)
        source.require(26)
        source.readShortLe() // version needed
        val flags = source.readShortLe().toInt() and 0xFFFF
        val method = source.readShortLe().toInt() and 0xFFFF
        source.readIntLe() // DOS time + date
        val crc = source.readIntLe().toLong() and 0xFFFF_FFFFL
        var compressedSize = source.readIntLe().toLong() and 0xFFFF_FFFFL
        var size = source.readIntLe().toLong() and 0xFFFF_FFFFL
        val nameLength = source.readShortLe().toInt() and 0xFFFF
        val extraLength = source.readShortLe().toInt() and 0xFFFF
        val name = source.readByteArray(nameLength.toLong()).decodeToString()
        val extra = source.readByteArray(extraLength.toLong())

        var zip64 = false
        if (size == UINT32_MAX || compressedSize == UINT32_MAX) {
            zip64Sizes(extra)?.let { (u, c) ->
                zip64 = true
                if (size == UINT32_MAX) size = u
                if (compressedSize == UINT32_MAX) compressedSize = c
            }
        }
        val hasDescriptor = flags and FLAG_DESCRIPTOR != 0
        val entry = when (method) {
            STORED -> {
                if (hasDescriptor) throw ZipFormatException("only DEFLATED entries can have EXT descriptor")
                EntrySource(StoredSource(source, size), crc, size, descriptor = false, zip64 = zip64)
            }
            DEFLATED -> EntrySource(InflatingSource(source, inflaters()), crc, size, hasDescriptor, zip64)
            else -> throw ZipFormatException("invalid compression method")
        }
        current = entry
        return ZipStreamEntry(name, entry.buffer())
    }

    /** The (uncompressed, compressed) sizes in a ZIP64 extended-information extra field. */
    private fun zip64Sizes(extra: ByteArray): Pair<Long, Long>? {
        val buffer = Buffer().write(extra)
        while (buffer.size >= 4) {
            val id = buffer.readShortLe().toInt() and 0xFFFF
            val length = (buffer.readShortLe().toInt() and 0xFFFF).toLong()
            if (length > buffer.size) return null
            if (id == ZIP64_EXTRA && length >= 16) return buffer.readLongLe() to buffer.readLongLe()
            buffer.skip(length)
        }
        return null
    }

    /** Counts and checksums one entry's data; checks both against the header or descriptor at its end. */
    private inner class EntrySource(
        private val data: EntryData,
        private var expectedCrc: Long,
        private var expectedSize: Long,
        private val descriptor: Boolean,
        private val zip64: Boolean,
    ) : Source {
        private val crc = Crc32()
        private var count = 0L
        private var finished = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (finished) return -1L
            val start = sink.size
            val read = data.read(sink, byteCount)
            if (read == -1L) {
                finish()
                return -1L
            }
            crc.update(Buffer().also { sink.copyTo(it, start, read) }.readByteArray())
            count += read
            return read
        }

        private fun finish() {
            finished = true
            data.release()
            if (descriptor) {
                source.require(4)
                if (source.buffer.getIntLe(0) == DESCRIPTOR) source.skip(4)
                expectedCrc = source.readIntLe().toLong() and 0xFFFF_FFFFL
                if (zip64) {
                    source.readLongLe()
                    expectedSize = source.readLongLe()
                } else {
                    source.readIntLe()
                    expectedSize = source.readIntLe().toLong() and 0xFFFF_FFFFL
                }
            }
            if (count != expectedSize) {
                throw ZipFormatException("invalid entry size (expected $expectedSize but got $count bytes)")
            }
            if (crc.value != expectedCrc) {
                throw ZipFormatException("invalid entry CRC (expected 0x${expectedCrc.toString(16)} but got 0x${crc.value.toString(16)})")
            }
        }

        fun drain() {
            val sink = Buffer()
            while (read(sink, DRAIN_BYTES) != -1L) sink.clear()
        }

        override fun timeout(): Timeout = source.timeout()

        /** The reader owns the stream; an entry is never closed on its own. */
        override fun close() = Unit
    }

    private companion object {
        const val LOCAL_HEADER = 0x04034b50
        const val DESCRIPTOR = 0x08074b50
        const val STORED = 0
        const val DEFLATED = 8
        const val FLAG_DESCRIPTOR = 0x08
        const val ZIP64_EXTRA = 0x0001
        const val UINT32_MAX = 0xFFFF_FFFFL
        const val DRAIN_BYTES = 64L * 1024
    }
}

private fun Buffer.getIntLe(at: Long): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)

/** An entry's raw data; [release] frees what it holds without closing the shared stream. */
private interface EntryData {
    fun read(sink: Buffer, byteCount: Long): Long
    fun release()
}

private class StoredSource(private val source: BufferedSource, private var remaining: Long) : EntryData {
    override fun read(sink: Buffer, byteCount: Long): Long {
        if (remaining == 0L) return -1L
        val read = source.read(sink, minOf(byteCount, remaining))
        if (read == -1L) throw ZipFormatException("unexpected EOF")
        remaining -= read
        return read
    }

    override fun release() = Unit
}

/** Raw deflate straight off the shared stream, consuming exactly the compressed bytes ([RawInflater]). */
private class InflatingSource(private val source: BufferedSource, private val inflater: RawInflater) : EntryData {
    private var released = false

    override fun read(sink: Buffer, byteCount: Long): Long = inflater.inflate(source, sink, byteCount)

    /** Frees the inflater (zlib memory on Android). */
    override fun release() {
        if (!released) inflater.end()
        released = true
    }
}

/** CRC-32 (IEEE 802.3), as `java.util.zip.CRC32`. */
internal class Crc32 {
    var value: Long = 0L
        private set

    fun update(bytes: ByteArray) {
        var c = value.toInt().inv()
        for (b in bytes) c = TABLE[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        value = c.inv().toLong() and 0xFFFF_FFFFL
    }

    private companion object {
        val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }
    }
}
