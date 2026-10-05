package com.myhealth.data.fit

import okio.Buffer
import okio.BufferedSource
import java.util.zip.DataFormatException
import java.util.zip.Inflater

actual fun newRawInflater(): RawInflater = JavaRawInflater()

/**
 * zlib through `java.util.zip.Inflater`. Input is handed over from a peek of the source and only
 * the bytes the inflater actually used are skipped, so nothing after the stream is consumed.
 */
class JavaRawInflater : RawInflater {

    private val inflater = Inflater(true)
    private val input = ByteArray(INPUT_BYTES)

    override fun inflate(source: BufferedSource, sink: Buffer, maxBytes: Long): Long {
        val out = ByteArray(minOf(maxBytes, OUTPUT_BYTES.toLong()).toInt())
        while (true) {
            if (inflater.finished()) return -1L
            if (inflater.needsInput()) {
                source.require(1)
                val count = minOf(source.buffer.size, INPUT_BYTES.toLong()).toInt()
                source.peek().readFully(input, count)
                inflater.setInput(input, 0, count)
            }
            val before = inflater.remaining
            val inflated = try {
                inflater.inflate(out)
            } catch (e: DataFormatException) {
                throw ZipFormatException(e.message ?: "invalid deflate data")
            }
            source.skip((before - inflater.remaining).toLong())
            if (inflated > 0) {
                sink.write(out, 0, inflated)
                return inflated.toLong()
            }
            if (inflater.needsDictionary()) throw ZipFormatException("deflate stream needs a preset dictionary")
        }
    }

    override fun end() = inflater.end()

    private companion object {
        const val INPUT_BYTES = 8 * 1024
        const val OUTPUT_BYTES = 64 * 1024
    }
}

private fun BufferedSource.readFully(into: ByteArray, count: Int) {
    var offset = 0
    while (offset < count) {
        val read = read(into, offset, count - offset)
        if (read == -1) throw okio.EOFException()
        offset += read
    }
}
