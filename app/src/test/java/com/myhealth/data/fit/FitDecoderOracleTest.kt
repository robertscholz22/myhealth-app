package com.myhealth.data.fit

import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.myhealth.domain.util.Outcome
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * P20.2: the shared [FitFileDecoder] must produce exactly what the Garmin FIT Java SDK decoder
 * ([SdkFitFileDecoder]) produced — same sessions, laps, records and enum names, and an error
 * exactly where the SDK failed.
 *
 * `fit04` runs over a whole directory of real files when `FIT_CORPUS` points at one (the owner's
 * Garmin export: tens of thousands of files, kept outside the repository); without it the test is skipped.
 */
class FitDecoderOracleTest {

    private val shared = FitFileDecoder()
    private val sdk = SdkFitFileDecoder()

    /** `null` when both agree, otherwise a one-line description of the difference. */
    private fun compare(bytes: ByteArray): String? {
        val a = shared.decode(bytes)
        val b = sdk.decode(ByteArrayInputStream(bytes))
        return when {
            a is Outcome.Ok && b is Outcome.Ok -> diff(a.value, b.value)
            a is Outcome.Err && b is Outcome.Err -> null
            a is Outcome.Ok -> "shared Ok, sdk ${(b as Outcome.Err).error}"
            else -> "shared ${(a as Outcome.Err).error}, sdk Ok"
        }
    }

    private fun diff(a: FitFileData, b: FitFileData): String? {
        if (a == b) return null
        if (a.fileId != b.fileId) return "fileId ${a.fileId} vs ${b.fileId}"
        if (a.localTimestampOffsetSec != b.localTimestampOffsetSec) {
            return "localOffset ${a.localTimestampOffsetSec} vs ${b.localTimestampOffsetSec}"
        }
        fun <T> first(name: String, x: List<T>, y: List<T>): String? {
            if (x.size != y.size) return "$name count ${x.size} vs ${y.size}"
            val i = x.indices.firstOrNull { x[it] != y[it] } ?: return null
            return "$name[$i] ${x[i]} vs ${y[i]}"
        }
        return first("session", a.sessions, b.sessions)
            ?: first("lap", a.laps, b.laps)
            ?: first("record", a.records, b.records)
    }

    @Test
    fun fit01_the_committed_fixtures_decode_identically() {
        listOf(RunFixtureEncoder.ensure(), RidePowerFixtureEncoder.ensure()).forEach { file ->
            assertWithMessage(file.name).that(compare(file.readBytes())).isNull()
        }
    }

    @Test
    fun fit02_enum_names_match_the_sdk() {
        val sports = Sport.entries.filter { it != Sport.INVALID }.associate { it.value.toInt() to it.name }
        val subSports = SubSport.entries.filter { it != SubSport.INVALID }.associate { it.value.toInt() to it.name }
        val files = com.garmin.fit.File.entries.filter { it != com.garmin.fit.File.INVALID }
            .associate { it.value.toInt() to it.name }
        assertThat(FitEnumNames.SPORT).isEqualTo(sports)
        assertThat(FitEnumNames.SUB_SPORT).isEqualTo(subSports)
        assertThat(FitEnumNames.FILE).isEqualTo(files)
    }

    @Test
    fun fit03_damaged_files_fail_like_the_sdk() {
        val good = RunFixtureEncoder.ensure().readBytes()
        val cases = mapOf(
            "empty" to ByteArray(0),
            "header only" to good.copyOf(14),
            "truncated" to good.copyOf(good.size / 2),
            "bad crc" to good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() },
            "flipped data byte" to good.copyOf().also { it[200] = (it[200].toInt() xor 0x10).toByte() },
            "not fit" to ByteArray(64) { 0x7 },
        )
        cases.forEach { (name, bytes) ->
            assertWithMessage(name).that(shared.decode(bytes)).isInstanceOf(Outcome.Err::class.java)
            assertWithMessage(name).that(compare(bytes)).isNull()
        }
    }

    @Test
    fun fit04_a_corpus_of_real_files_decodes_identically() {
        val dir = System.getenv("FIT_CORPUS")?.let(::File)
        assumeTrue("FIT_CORPUS not set", dir != null && dir.isDirectory)
        val files = dir!!.walkTopDown().filter { it.isFile && it.extension.equals("fit", ignoreCase = true) }.toList()
        val mismatches = files.mapNotNull { f -> compare(f.readBytes())?.let { "${f.name}: $it" } }
        val kinds = mismatches.groupingBy { it.substringAfter(": ").substringBefore(' ') }.eachCount()
        val decoded = files.map { shared.decode(it.readBytes()) }.filterIsInstance<Outcome.Ok<FitFileData>>().map { it.value }
        println(
            "FIT corpus: ${files.size} files (${decoded.size} decoded, ${decoded.count { it.sessions.isNotEmpty() }} with " +
                "sessions, ${decoded.sumOf { it.records.size }} records), ${mismatches.size} mismatches $kinds",
        )
        mismatches.take(15).forEach { println("  " + it.take(400)) }
        assertThat(mismatches).isEmpty()
    }
}
