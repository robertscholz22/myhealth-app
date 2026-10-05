package com.myhealth.data.fit

import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome

/**
 * Reads a FIT activity file into the SDK-free [FitFileData] (PLAN P7.1).
 *
 * P20.2: a plain-Kotlin decoder replacing the Garmin FIT Java SDK, which does not run on iOS. It
 * reads only what the app needs — `file_id`, `session`, `lap`, `record` and `activity` messages,
 * and of those only the fields [FitFileData] carries — but it walks the whole binary format to get
 * there: 12/14-byte headers, definition and data messages, both byte orders, developer fields
 * (skipped), compressed-timestamp headers and chained files. Values follow the SDK getters it
 * replaces: the profile's scale/offset applied in `Double` and narrowed to `Float` where the SDK
 * returns a `Float`, invalid values read as absent, enum names as the SDK spells them
 * ([FitEnumNames]). `FitDecoderOracleTest` compares it with the SDK, which stays as a test
 * dependency.
 *
 * Failure model (§1.5): a truncated, corrupt (file CRC) or non-FIT stream becomes
 * [AppError.Parse] rather than an exception — one bad file inside a Garmin export ZIP must not
 * abort the whole import (P7.5).
 */
class FitFileDecoder {

    fun decode(bytes: ByteArray): Outcome<FitFileData> = try {
        Outcome.Ok(FitReader(bytes).read())
    } catch (e: FitFormatException) {
        Outcome.Err(AppError.Parse(what = "fit", detail = e.message ?: "not a readable FIT file"))
    } catch (e: RuntimeException) {
        Outcome.Err(AppError.Parse(what = "fit", detail = e.message ?: (e::class.simpleName ?: "decode error")))
    }
}

/** A stream that is not (or no longer) a valid FIT file. */
class FitFormatException(message: String) : RuntimeException(message)

/** One field of a definition message: number, size in bytes, base type byte. */
private class FieldDef(val num: Int, val size: Int, val baseType: Int)

/** A local message definition; [devBytes] is the total size of its developer fields. */
private class MessageDef(val global: Int, val bigEndian: Boolean, val fields: List<FieldDef>, val devBytes: Int)

/** A decoded data message: field number → the raw first value (`Long`, `Double` or `String`). */
private class Values(private val map: Map<Int, Any>) {

    fun long(num: Int): Long? = (map[num] as? Number)?.toLong()

    fun int(num: Int): Int? = long(num)?.toInt()

    fun string(num: Int): String? = map[num] as? String

    /** The SDK's `Float` getters: `raw / scale - offset` in `Double`, narrowed to `Float`. */
    fun float(num: Int, scale: Double = 1.0, offset: Double = 0.0): Double? =
        (map[num] as? Number)?.let { (it.toDouble() / scale - offset).toFloat().toDouble() }

    fun enumName(num: Int, names: Map<Int, String>): String? =
        int(num)?.let { names[it] ?: FitEnumNames.INVALID }
}

private class FitReader(private val bytes: ByteArray) {

    private var pos = 0
    private val defs = arrayOfNulls<MessageDef>(LOCAL_TYPES)
    private var timestamp = 0L
    private var lastTimeOffset = 0
    private val collector = Collector()

    fun read(): FitFileData {
        if (bytes.isEmpty()) throw FitFormatException("FIT decode error: empty file.")
        // A FIT stream may hold several files back to back ("chained" FIT); each has its own header.
        while (pos < bytes.size) readFile()
        return collector.toData()
    }

    private fun readFile() {
        val start = pos
        need(start, 1)
        val headerSize = u8(start)
        if (headerSize < MIN_HEADER) throw FitFormatException("FIT decode error: invalid header size $headerSize.")
        need(start, headerSize)
        if (!(u8(start + 8) == '.'.code && u8(start + 9) == 'F'.code && u8(start + 10) == 'I'.code && u8(start + 11) == 'T'.code)) {
            throw FitFormatException("FIT decode error: File is not FIT format.")
        }
        val dataSize = uint(start + 4, 4, bigEndian = false)
        if (start + headerSize + dataSize + CRC_SIZE > bytes.size) {
            throw FitFormatException("FIT decode error: unexpected end of file.")
        }
        val end = start + headerSize + dataSize.toInt()
        if (Crc16.of(bytes, start, end + CRC_SIZE) != 0) throw FitFormatException("FIT decode error: File CRC failed.")

        defs.fill(null)
        pos = start + headerSize
        while (pos < end) readRecord(end)
        if (pos != end) throw FitFormatException("FIT decode error: message runs past the data size.")
        pos = end + CRC_SIZE
    }

    private fun readRecord(end: Int) {
        need(pos, 1, end)
        val header = u8(pos++)
        when {
            header and COMPRESSED_TS != 0 -> {
                val offset = header and TIME_OFFSET_MASK
                timestamp += ((offset - lastTimeOffset) and TIME_OFFSET_MASK).toLong()
                lastTimeOffset = offset
                readData((header shr 5) and 0x03, end, compressedTimestamp = timestamp)
            }
            header and DEFINITION != 0 -> readDefinition(header and LOCAL_MASK, header and DEV_DATA != 0, end)
            else -> readData(header and LOCAL_MASK, end, compressedTimestamp = null)
        }
    }

    private fun readDefinition(local: Int, hasDevFields: Boolean, end: Int) {
        need(pos, 5, end)
        val bigEndian = u8(pos + 1) == 1
        val global = uint(pos + 2, 2, bigEndian).toInt()
        val count = u8(pos + 4)
        pos += 5
        need(pos, count * 3, end)
        val fields = List(count) { i -> FieldDef(u8(pos + i * 3), u8(pos + i * 3 + 1), u8(pos + i * 3 + 2)) }
        pos += count * 3
        var devBytes = 0
        if (hasDevFields) {
            need(pos, 1, end)
            val devCount = u8(pos++)
            need(pos, devCount * 3, end)
            repeat(devCount) { i -> devBytes += u8(pos + i * 3 + 1) }
            pos += devCount * 3
        }
        defs[local] = MessageDef(global, bigEndian, fields, devBytes)
    }

    private fun readData(local: Int, end: Int, compressedTimestamp: Long?) {
        val def = defs[local] ?: throw FitFormatException("FIT decode error: Missing message definition for local message number $local.")
        val wanted = WANTED[def.global]
        val values = HashMap<Int, Any>()
        for (field in def.fields) {
            need(pos, field.size, end)
            if (field.num == TIMESTAMP_FIELD || (wanted != null && field.num in wanted)) {
                readValue(field, def.bigEndian)?.let { values[field.num] = it }
            }
            pos += field.size
        }
        need(pos, def.devBytes, end)
        pos += def.devBytes

        val ts = values[TIMESTAMP_FIELD] as? Long
        if (ts != null) {
            timestamp = ts
            lastTimeOffset = (ts and TIME_OFFSET_MASK.toLong()).toInt()
        } else if (compressedTimestamp != null) {
            values[TIMESTAMP_FIELD] = compressedTimestamp
        }
        if (wanted != null) collector.accept(def.global, Values(values))
    }

    /** The field's first value per its declared base type, or `null` when it is the invalid value. */
    private fun readValue(field: FieldDef, bigEndian: Boolean): Any? {
        val type = field.baseType and BASE_TYPE_MASK
        if (type == STRING) {
            var len = 0
            while (len < field.size && bytes[pos + len] != 0.toByte()) len++
            return if (len == 0) null else bytes.decodeToString(pos, pos + len)
        }
        val size = ELEMENT_SIZE.getOrNull(type) ?: return null
        if (field.size < size) return null
        return when (type) {
            FLOAT32 -> {
                val bits = uint(pos, 4, bigEndian)
                if (bits == 0xFFFF_FFFFL) null else Float.fromBits(bits.toInt()).toDouble()
            }
            FLOAT64 -> {
                val bits = ulong(pos, bigEndian)
                if (bits == -1L) null else Double.fromBits(bits)
            }
            else -> {
                val raw = if (size == 8) ulong(pos, bigEndian) else uint(pos, size, bigEndian)
                if (raw == INVALID[type]) {
                    null
                } else if (SIGNED[type] && size < 8) {
                    // Sign-extend from the element width.
                    val shift = 64 - size * 8
                    (raw shl shift) shr shift
                } else {
                    raw
                }
            }
        }
    }

    private fun need(at: Int, count: Int, limit: Int = bytes.size) {
        if (count < 0 || at + count > limit) throw FitFormatException("FIT decode error: unexpected end of file.")
    }

    private fun u8(at: Int): Int = bytes[at].toInt() and 0xFF

    /** Unsigned little/big-endian integer of 1…4 bytes (as `Long`, so 32 bits stay positive). */
    private fun uint(at: Int, size: Int, bigEndian: Boolean): Long {
        var v = 0L
        for (i in 0 until size) {
            val b = u8(if (bigEndian) at + i else at + size - 1 - i).toLong()
            v = (v shl 8) or b
        }
        return v
    }

    private fun ulong(at: Int, bigEndian: Boolean): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or u8(if (bigEndian) at + i else at + 7 - i).toLong()
        }
        return v
    }

    private companion object {
        const val MIN_HEADER = 12
        const val CRC_SIZE = 2
        const val LOCAL_TYPES = 16
        const val COMPRESSED_TS = 0x80
        const val DEFINITION = 0x40
        const val DEV_DATA = 0x20
        const val LOCAL_MASK = 0x0F
        const val TIME_OFFSET_MASK = 0x1F
        const val BASE_TYPE_MASK = 0x1F
        const val TIMESTAMP_FIELD = 253

        const val STRING = 7
        const val FLOAT32 = 8
        const val FLOAT64 = 9

        /** Element size of base types 0…16 (enum, sint8, uint8, sint16, …, uint64z). */
        val ELEMENT_SIZE = intArrayOf(1, 1, 1, 2, 2, 4, 4, 1, 4, 8, 1, 2, 4, 1, 8, 8, 8)
        val SIGNED = booleanArrayOf(false, true, false, true, false, true, false, false, true, true, false, false, false, false, true, false, false)
        val INVALID = longArrayOf(
            0xFF, 0x7F, 0xFF, 0x7FFF, 0xFFFF, 0x7FFF_FFFF, 0xFFFF_FFFFL, 0, 0xFFFF_FFFFL, -1,
            0, 0, 0, 0xFF, Long.MAX_VALUE, -1, 0,
        )

        /** The fields read per global message; everything else is skipped unread. */
        val WANTED: Map<Int, Set<Int>> = mapOf(
            FitProfile.FILE_ID to setOf(0, 1, 2, 3, 4),
            FitProfile.SESSION to setOf(2, 5, 6, 7, 8, 9, 11, 14, 15, 16, 17, 18, 20, 21, 22, 34, 110),
            FitProfile.LAP to setOf(2, 7, 8, 9, 11, 13, 15, 16, 254),
            FitProfile.RECORD to setOf(0, 1, 2, 3, 4, 5, 6, 7, 73, 78),
            FitProfile.ACTIVITY to setOf(5),
        )
    }
}

/** Global message numbers and the profile's scale/offset for the fields read (SDK 21.214). */
internal object FitProfile {
    const val FILE_ID = 0
    const val SESSION = 18
    const val LAP = 19
    const val RECORD = 20
    const val ACTIVITY = 34

    const val TIME_SCALE = 1000.0
    const val DISTANCE_SCALE = 100.0
    const val SPEED_SCALE = 1000.0
    const val ALTITUDE_SCALE = 5.0
    const val ALTITUDE_OFFSET = 500.0
}

/** Copies the fields [FitFileData] carries out of the decoded messages, as the SDK listeners did. */
private class Collector {

    private var fileId: FitFileId? = null
    private var localOffsetSec: Long? = null
    private val sessions = mutableListOf<FitSession>()
    private val laps = mutableListOf<FitLap>()
    private val records = mutableListOf<FitRecord>()

    fun toData(): FitFileData = FitFileData(
        fileId = fileId,
        sessions = sessions.sortedBy { it.startAtMillis },
        laps = laps.sortedBy { it.startAtMillis },
        records = records.sortedBy { it.timestampMillis },
        localTimestampOffsetSec = localOffsetSec,
    )

    fun accept(global: Int, v: Values) {
        when (global) {
            FitProfile.FILE_ID -> fileId = FitFileId(
                type = v.enumName(0, FitEnumNames.FILE),
                manufacturer = v.int(1),
                product = v.int(2),
                serialNumber = v.long(3),
                timeCreatedMillis = v.long(4)?.let(FitEpoch::toUnixMillis),
            )
            FitProfile.SESSION -> session(v)
            FitProfile.LAP -> lap(v)
            FitProfile.RECORD -> record(v)
            FitProfile.ACTIVITY -> {
                val local = v.long(5) ?: return
                val utc = v.long(253) ?: return
                localOffsetSec = local - utc
            }
        }
    }

    private fun session(v: Values) {
        val start = v.long(2) ?: return
        sessions += FitSession(
            startAtMillis = FitEpoch.toUnixMillis(start),
            sport = v.enumName(5, FitEnumNames.SPORT),
            subSport = v.enumName(6, FitEnumNames.SUB_SPORT),
            sportProfileName = v.string(110),
            totalElapsedSec = v.float(7, FitProfile.TIME_SCALE),
            totalTimerSec = v.float(8, FitProfile.TIME_SCALE),
            totalDistanceMeters = v.float(9, FitProfile.DISTANCE_SCALE),
            totalCalories = v.int(11),
            avgHr = v.int(16),
            maxHr = v.int(17),
            avgSpeedMps = v.float(14, FitProfile.SPEED_SCALE),
            maxSpeedMps = v.float(15, FitProfile.SPEED_SCALE),
            avgCadenceSpm = v.long(18)?.toDouble(),
            totalAscentM = v.long(22)?.toDouble(),
            avgPowerW = v.int(20),
            maxPowerW = v.int(21),
            normalizedPowerW = v.int(34),
        )
    }

    private fun lap(v: Values) {
        val start = v.long(2) ?: return
        laps += FitLap(
            messageIndex = v.int(254),
            startAtMillis = FitEpoch.toUnixMillis(start),
            totalElapsedSec = v.float(7, FitProfile.TIME_SCALE),
            totalTimerSec = v.float(8, FitProfile.TIME_SCALE),
            totalDistanceMeters = v.float(9, FitProfile.DISTANCE_SCALE),
            totalCalories = v.int(11),
            avgHr = v.int(15),
            maxHr = v.int(16),
            avgSpeedMps = v.float(13, FitProfile.SPEED_SCALE),
        )
    }

    private fun record(v: Values) {
        val timestamp = v.long(253) ?: return
        records += FitRecord(
            timestampMillis = FitEpoch.toUnixMillis(timestamp),
            hr = v.int(3),
            distanceMeters = v.float(5, FitProfile.DISTANCE_SCALE),
            // The "enhanced" variants are 32-bit and cover speeds/altitudes the 16-bit
            // originals overflow; devices that write both keep them in sync.
            speedMps = v.float(73, FitProfile.SPEED_SCALE) ?: v.float(6, FitProfile.SPEED_SCALE),
            cadenceSpm = v.int(4),
            altitudeM = v.float(78, FitProfile.ALTITUDE_SCALE, FitProfile.ALTITUDE_OFFSET)
                ?: v.float(2, FitProfile.ALTITUDE_SCALE, FitProfile.ALTITUDE_OFFSET),
            powerW = v.int(7),
            positionLatSemicircles = v.int(0),
            positionLongSemicircles = v.int(1),
        )
    }
}

/** The FIT CRC-16 (nibble table from the FIT protocol document). */
internal object Crc16 {
    private val TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    fun of(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            val b = bytes[i].toInt() and 0xFF
            var tmp = TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor TABLE[b and 0xF]
            tmp = TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor TABLE[(b shr 4) and 0xF]
        }
        return crc
    }
}
