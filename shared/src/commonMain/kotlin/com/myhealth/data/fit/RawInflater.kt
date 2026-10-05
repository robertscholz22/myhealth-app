package com.myhealth.data.fit

import okio.Buffer
import okio.BufferedSource

/**
 * Raw DEFLATE (RFC 1951, no zlib/gzip wrapper) that consumes **exactly** the compressed bytes from
 * its source, so a [ZipStreamReader] can read the data descriptor and the next local header right
 * after an entry whose compressed size the header does not state. okio's `InflaterSource` cannot
 * be used for that: it reads its source past the end of the deflate stream.
 */
interface RawInflater {

    /** Inflates up to [maxBytes] into [sink]; the count written, or -1 once the stream has ended. */
    fun inflate(source: BufferedSource, sink: Buffer, maxBytes: Long): Long

    /** Frees native resources; the inflater is not used afterwards. */
    fun end()
}

/** `java.util.zip.Inflater` on Android (zlib, native speed); [KotlinRawInflater] on iOS. */
expect fun newRawInflater(): RawInflater

/**
 * DEFLATE decoder in plain Kotlin, after Mark Adler's `puff.c`: canonical Huffman codes decoded
 * bit by bit, so it never reads a byte beyond the end of the stream. Used on iOS; on the JVM it is
 * checked against `java.util.zip` in `ZipStreamReaderTest`.
 */
class KotlinRawInflater : RawInflater {

    private lateinit var source: BufferedSource
    private var bitBuffer = 0
    private var bitCount = 0

    private val window = ByteArray(WINDOW)
    private var windowPos = 0
    private var total = 0L

    private var state = HEADER
    private var lastBlock = false
    private var storedLeft = 0
    private var copyLength = 0
    private var copyDistance = 0
    private var literals: Huffman = FIXED_LITERALS
    private var distances: Huffman = FIXED_DISTANCES

    override fun inflate(source: BufferedSource, sink: Buffer, maxBytes: Long): Long {
        this.source = source
        if (state == DONE) return -1L
        val want = minOf(maxBytes, MAX_CHUNK.toLong()).toInt()
        val out = ByteArray(want)
        var n = 0
        while (n < want) {
            if (copyLength > 0) {
                while (copyLength > 0 && n < want) {
                    val b = window[(windowPos - copyDistance) and WINDOW_MASK]
                    out[n++] = b
                    put(b)
                    copyLength--
                }
                continue
            }
            when (state) {
                HEADER -> {
                    if (lastBlock) {
                        state = DONE
                        break
                    }
                    lastBlock = bits(1) == 1
                    when (bits(2)) {
                        0 -> startStored()
                        1 -> {
                            literals = FIXED_LITERALS
                            distances = FIXED_DISTANCES
                            state = CODES
                        }
                        2 -> {
                            readDynamicTables()
                            state = CODES
                        }
                        else -> throw ZipFormatException("invalid block type")
                    }
                }
                STORED -> {
                    if (storedLeft == 0) {
                        state = HEADER
                        continue
                    }
                    val count = minOf(storedLeft, want - n)
                    var copied = 0
                    while (copied < count) {
                        val read = source.read(out, n + copied, count - copied)
                        if (read == -1) throw okio.EOFException("unexpected end of deflate stream")
                        copied += read
                    }
                    for (i in n until n + count) put(out[i])
                    n += count
                    storedLeft -= count
                }
                CODES -> {
                    val symbol = decode(literals)
                    when {
                        symbol < 256 -> {
                            out[n++] = symbol.toByte()
                            put(symbol.toByte())
                        }
                        symbol == 256 -> state = HEADER
                        else -> startCopy(symbol - 257)
                    }
                }
            }
        }
        if (n == 0) return -1L
        sink.write(out, 0, n)
        return n.toLong()
    }

    override fun end() = Unit

    private fun put(b: Byte) {
        window[windowPos] = b
        windowPos = (windowPos + 1) and WINDOW_MASK
        total++
    }

    private fun bits(need: Int): Int {
        while (bitCount < need) {
            bitBuffer = bitBuffer or ((source.readByte().toInt() and 0xFF) shl bitCount)
            bitCount += 8
        }
        val value = bitBuffer and ((1 shl need) - 1)
        bitBuffer = bitBuffer ushr need
        bitCount -= need
        return value
    }

    private fun startStored() {
        bitBuffer = 0
        bitCount = 0
        val length = source.readShortLe().toInt() and 0xFFFF
        val complement = source.readShortLe().toInt() and 0xFFFF
        if (length != complement.inv() and 0xFFFF) throw ZipFormatException("invalid stored block lengths")
        storedLeft = length
        state = STORED
    }

    private fun startCopy(lengthSymbol: Int) {
        if (lengthSymbol >= LENGTH_BASE.size) throw ZipFormatException("invalid literal/length code")
        val length = LENGTH_BASE[lengthSymbol] + bits(LENGTH_EXTRA[lengthSymbol])
        val distanceSymbol = decode(distances)
        if (distanceSymbol >= DISTANCE_BASE.size) throw ZipFormatException("invalid distance code")
        val distance = DISTANCE_BASE[distanceSymbol] + bits(DISTANCE_EXTRA[distanceSymbol])
        if (distance > total) throw ZipFormatException("invalid distance too far back")
        copyLength = length
        copyDistance = distance
    }

    private fun decode(h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (length in 1..MAX_BITS) {
            code = code or bits(1)
            val count = h.count[length]
            if (code - count < first) return h.symbol[index + (code - first)]
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        throw ZipFormatException("invalid code")
    }

    private fun readDynamicTables() {
        val nLengths = bits(5) + 257
        val nDistances = bits(5) + 1
        val nCodes = bits(4) + 4
        if (nLengths > 286 || nDistances > 30) throw ZipFormatException("too many length or distance symbols")
        val lengths = IntArray(320)
        for (i in 0 until nCodes) lengths[CODE_ORDER[i]] = bits(3)
        val (codeCode, codeLeft) = Huffman.build(lengths, 0, 19)
        if (codeLeft != 0) throw ZipFormatException("invalid code lengths set")
        var index = 0
        while (index < nLengths + nDistances) {
            val symbol = decode(codeCode)
            if (symbol < 16) {
                lengths[index++] = symbol
                continue
            }
            var length = 0
            val repeat = when (symbol) {
                16 -> {
                    if (index == 0) throw ZipFormatException("invalid bit length repeat")
                    length = lengths[index - 1]
                    3 + bits(2)
                }
                17 -> 3 + bits(3)
                else -> 11 + bits(7)
            }
            if (index + repeat > nLengths + nDistances) throw ZipFormatException("invalid bit length repeat")
            repeat(repeat) { lengths[index++] = length }
        }
        if (lengths[256] == 0) throw ZipFormatException("invalid code -- missing end-of-block")
        val (lit, litLeft) = Huffman.build(lengths, 0, nLengths)
        if (litLeft < 0 || (litLeft > 0 && nLengths - lit.count[0] != 1)) {
            throw ZipFormatException("invalid literal/lengths set")
        }
        val (dist, distLeft) = Huffman.build(lengths, nLengths, nDistances)
        if (distLeft < 0 || (distLeft > 0 && nDistances - dist.count[0] != 1)) {
            throw ZipFormatException("invalid distances set")
        }
        literals = lit
        distances = dist
    }

    /** A canonical Huffman code: number of codes per length, and the symbols in code order. */
    private class Huffman(val count: IntArray, val symbol: IntArray) {
        companion object {
            /** The code for `lengths[from until from + n]`, and how incomplete it is (<0: over-subscribed). */
            fun build(lengths: IntArray, from: Int, n: Int): Pair<Huffman, Int> {
                val count = IntArray(MAX_BITS + 1)
                for (i in 0 until n) count[lengths[from + i]]++
                val h = Huffman(count, IntArray(n))
                if (count[0] == n) return h to 0
                var left = 1
                for (length in 1..MAX_BITS) {
                    left = (left shl 1) - count[length]
                    if (left < 0) return h to left
                }
                val offsets = IntArray(MAX_BITS + 1)
                for (length in 1 until MAX_BITS) offsets[length + 1] = offsets[length] + count[length]
                for (i in 0 until n) {
                    val length = lengths[from + i]
                    if (length != 0) h.symbol[offsets[length]++] = i
                }
                return h to left
            }
        }
    }

    private companion object {
        const val HEADER = 0
        const val STORED = 1
        const val CODES = 2
        const val DONE = 3

        const val MAX_BITS = 15
        const val WINDOW = 32 * 1024
        const val WINDOW_MASK = WINDOW - 1
        const val MAX_CHUNK = 64 * 1024

        val LENGTH_BASE = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
        )
        val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        val DISTANCE_BASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073,
            4097, 6145, 8193, 12289, 16385, 24577,
        )
        val DISTANCE_EXTRA = intArrayOf(
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
        )
        val CODE_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

        val FIXED_LITERALS: Huffman = Huffman.build(
            IntArray(288) { i -> if (i < 144) 8 else if (i < 256) 9 else if (i < 280) 7 else 8 }, 0, 288,
        ).first
        val FIXED_DISTANCES: Huffman = Huffman.build(IntArray(30) { 5 }, 0, 30).first
    }
}
