package com.myhealth.data.fit

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.SportType
import kotlinx.datetime.TimeZone
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The named cases of PLAN P7.3 over the three fixtures in
 * `app/src/test/resources/fixtures/csv/`: a standard English export, a German-locale export and a
 * sparse export that is missing most known columns.
 */
class GarminCsvParserTest {

    private val zone = TimeZone.of("Europe/Berlin")
    private val parser = GarminCsvParser(zone)

    private fun parse(name: String) = parser.parse(loadCsvFixture(name))

    @Test
    fun csv01_standard_english_export() {
        val result = parse("garmin_en")

        assertThat(result.errors).isEmpty()
        assertThat(result.rows).hasSize(5)

        val run = result.rows.first()
        assertThat(run.sportType).isEqualTo(SportType.RUN_OUTDOOR)
        assertThat(run.startAtMillis).isEqualTo(RUN_START)
        assertThat(run.distanceMeters).isEqualTo(5_000.0)
        assertThat(run.calories).isEqualTo(355.0)
        assertThat(run.durationSec).isEqualTo(1_500)
        assertThat(run.avgHr).isEqualTo(152)
        assertThat(run.maxHr).isEqualTo(178)
        assertThat(run.elevationGainM).isEqualTo(42.0)
        assertThat(run.aerobicTrainingEffect).isEqualTo(3.4)
        // "Avg Pace" 5:00 min/km and "Best Pace" 4:04 min/km become metres per second.
        assertThat(checkNotNull(run.avgSpeedMps)).isWithin(1e-4).of(3.3333)
        assertThat(checkNotNull(run.maxSpeedMps)).isWithin(1e-4).of(1000.0 / 244.0)

        val ride = result.rows[1]
        assertThat(ride.sportType).isEqualTo(SportType.CYCLING)
        assertThat(ride.distanceMeters).isEqualTo(30_250.0)
        assertThat(ride.durationSec).isEqualTo(3_930)
        assertThat(result.rows[2].sportType).isEqualTo(SportType.SOCCER_TRAINING)
        assertThat(result.rows[3].sportType).isEqualTo(SportType.STRENGTH)
    }

    @Test
    fun csv02_german_locale_decimals() {
        val result = parse("garmin_de")

        assertThat(result.errors).isEmpty()
        assertThat(result.rows).hasSize(3)

        val run = result.rows.first()
        assertThat(run.sportType).isEqualTo(SportType.RUN_OUTDOOR)
        assertThat(run.startAtMillis).isEqualTo(RUN_START)
        // "10,52" km with a decimal comma, "1.234" kcal with a thousands dot.
        assertThat(run.distanceMeters).isEqualTo(10_520.0)
        assertThat(run.calories).isEqualTo(1_234.0)
        assertThat(run.durationSec).isEqualTo(3_150)
        assertThat(run.avgHr).isEqualTo(151)
        assertThat(run.maxHr).isEqualTo(177)
        assertThat(run.elevationGainM).isEqualTo(86.0)
        assertThat(result.rows[1].distanceMeters).isEqualTo(30_250.0)
        assertThat(result.rows[2].sportType).isEqualTo(SportType.STRENGTH)
    }

    @Test
    fun csv03_quoted_title_with_comma() {
        assertThat(parse("garmin_en").rows.first().title).isEqualTo("Morning run, easy")
        assertThat(parse("garmin_de").rows[1].title).isEqualTo("Feierabendrunde, flach")
        // A doubled quote inside a quoted field is one literal quote.
        assertThat(parse("garmin_sparse").rows[1].title).isEqualTo("Evening \"flow\" session")
    }

    @Test
    fun csv04_missing_columns_tolerated() {
        val result = parse("garmin_sparse")

        assertThat(result.errors).isEmpty()
        assertThat(result.rows).hasSize(2)

        val walk = result.rows.first()
        assertThat(walk.sportType).isEqualTo(SportType.WALK)
        assertThat(walk.title).isEqualTo("Lunch walk")
        assertThat(walk.calories).isEqualTo(150.0)
        assertThat(walk.distanceMeters).isNull()
        assertThat(walk.durationSec).isNull()
        assertThat(walk.avgHr).isNull()
        assertThat(walk.maxHr).isNull()
        assertThat(walk.elevationGainM).isNull()
        assertThat(result.rows[1].sportType).isEqualTo(SportType.MOBILITY)
    }

    @Test
    fun csv05_dash_means_null() {
        val rows = parse("garmin_en").rows

        val strength = rows[3]
        assertThat(strength.distanceMeters).isNull()
        assertThat(strength.avgSpeedMps).isNull()
        assertThat(strength.maxSpeedMps).isNull()
        assertThat(strength.elevationGainM).isNull()
        assertThat(strength.calories).isEqualTo(310.0)

        val pickleball = rows[4]
        assertThat(pickleball.avgHr).isNull()
        assertThat(pickleball.maxHr).isNull()
        assertThat(pickleball.aerobicTrainingEffect).isNull()
    }

    @Test
    fun csv06_duration_formats() {
        assertThat(parseDuration("00:25:00")).isEqualTo(1_500)
        assertThat(parseDuration("25:00")).isEqualTo(1_500)
        assertThat(parseDuration("1:05:30")).isEqualTo(3_930)
        assertThat(parseDuration("45:00")).isEqualTo(2_700)
        assertThat(parseDuration("00:00:07")).isEqualTo(7)
        assertThat(parseDuration("90")).isEqualTo(90)
        assertThat(parseDuration("1:02:03:04")).isNull()
        assertThat(parseDuration("not a time")).isNull()
        // The parsed rows agree with the standalone helper.
        assertThat(parse("garmin_en").rows.map { it.durationSec })
            .containsExactly(1_500, 3_930, 5_400, 2_700, 1_800).inOrder()
    }

    @Test
    fun csv07_unknown_activity_type_is_other() {
        val pickleball = parse("garmin_en").rows[4]

        assertThat(pickleball.activityTypeRaw).isEqualTo("Pickleball")
        assertThat(pickleball.sportType).isEqualTo(SportType.OTHER)
        assertThat(GarminActivityTypeMap.toSportType(null)).isEqualTo(SportType.OTHER)
        assertThat(GarminActivityTypeMap.toSportType("Backcountry Skiing")).isEqualTo(SportType.OTHER)
        // The long tail still resolves through the contains-fallbacks.
        assertThat(GarminActivityTypeMap.toSportType("Virtual Running")).isEqualTo(SportType.RUN_OUTDOOR)
        assertThat(GarminActivityTypeMap.toSportType("eBike Ride")).isEqualTo(SportType.CYCLING)
    }

    @Test
    fun csv08_row_hash_is_stable() {
        val first = parse("garmin_en").rows.map { it.externalId }
        val second = parse("garmin_en").rows.map { it.externalId }

        assertThat(first).isEqualTo(second)
        assertThat(first.toSet()).hasSize(5)
        first.forEach { assertThat(it).hasLength(64) }

        val run = parse("garmin_en").rows.first()
        assertThat(run.externalId).isEqualTo(sha256Hex(run.rawRow))

        // Any change to the row text is a different activity_source_record.
        val edited = parser.parse(
            loadCsvFixture("garmin_en").replace("Morning run, easy", "Morning run, hard"),
        )
        assertThat(edited.rows.first().externalId).isNotEqualTo(run.externalId)
    }

    /**
     * BUG-11: the owner's real Garmin export has **German headers and English numbers**. The
     * format must be decided by the numeric cells, so "7.20" is 7.20 km and not 720 km, and
     * "1,057" is 1057 kcal and not 1.057.
     */
    @Test
    fun csv09_german_headers_with_english_numbers() {
        val result = parse(OWNER_FIXTURE)

        assertThat(result.errors).isEmpty()
        assertThat(result.rows).hasSize(20)

        val soccer = result.rows.first()
        assertThat(soccer.sportType).isEqualTo(SportType.SOCCER_TRAINING)
        assertThat(soccer.distanceMeters).isEqualTo(7_200.0)
        assertThat(soccer.calories).isEqualTo(1_057.0)
        assertThat(soccer.durationSec).isEqualTo(5_374)
        assertThat(soccer.avgHr).isEqualTo(154)
        assertThat(soccer.maxHr).isEqualTo(186)
        assertThat(soccer.aerobicTrainingEffect).isEqualTo(4.1)

        // The 5.01 km run of 5 Sep, the one that produced no 5 km PR while BUG-11 was open.
        val run = result.rows[3]
        assertThat(run.sportType).isEqualTo(SportType.RUN_OUTDOOR)
        assertThat(run.distanceMeters).isEqualTo(5_010.0)
        assertThat(run.durationSec).isEqualTo(1_317)
        assertThat(run.calories).isEqualTo(323.0)
        // "Ø Geschwindigkeit" holds the pace 4:23 min/km for a run.
        assertThat(checkNotNull(run.avgSpeedMps)).isWithin(1e-4).of(1_000.0 / 263.0)

        assertThat(result.rows[17].sportType).isEqualTo(SportType.CYCLING)
        assertThat(result.rows.count { it.sportType == SportType.SOCCER_TRAINING }).isEqualTo(7)
        assertThat(result.rows.count { it.sportType == SportType.RUN_OUTDOOR }).isEqualTo(8)
        assertThat(result.rows.count { it.sportType == SportType.CYCLING }).isEqualTo(5)
    }

    /**
     * P12: the four power/cadence columns are read from both the English and the German export.
     * The rows are copied verbatim out of the owner's real export — the 60-minute Zwift ride of
     * 5 Feb (avg 166 W, max 310 W, NP 167 W, 91 rpm) and a 5 km run, which Garmin also writes a
     * running power for. The German header spells the running and the cycling cadence column
     * identically, so the parser has to take the first non-empty of the two.
     */
    @Test
    fun bike06_csv_power_columns_en_and_de() {
        for (fixture in listOf("garmin_de_power", "garmin_en_power")) {
            val result = parse(fixture)
            assertThat(result.errors).isEmpty()

            val ride = result.rows.first()
            assertThat(ride.sportType).isEqualTo(SportType.CYCLING_INDOOR)
            assertThat(ride.avgPowerW).isEqualTo(166)
            assertThat(ride.maxPowerW).isEqualTo(310)
            assertThat(ride.normalizedPowerW).isEqualTo(167)
            // A ride's cadence column is the bike one, in rpm.
            assertThat(checkNotNull(ride.avgCadenceSpm)).isWithin(1e-9).of(91.0)

            val run = result.rows[1]
            assertThat(run.sportType).isEqualTo(SportType.RUN_OUTDOOR)
            // Garmin records running power too; it lands in the same columns.
            assertThat(run.avgPowerW).isEqualTo(389)
            assertThat(run.maxPowerW).isEqualTo(504)
            assertThat(run.normalizedPowerW).isEqualTo(391)
            // …and the run's cadence comes from the *running* cadence column, in steps/min.
            assertThat(checkNotNull(run.avgCadenceSpm)).isWithin(1e-9).of(182.0)

            // The candidate session carries them through to the merger (§2.4).
            val session = parser.toIngestItem(ride, nowMillis = 1L).session
            assertThat(session.avgPowerW).isEqualTo(166)
            assertThat(session.normalizedPowerW).isEqualTo(167)
            assertThat(session.avgCadenceSpm).isEqualTo(91.0)
        }
    }

    /**
     * P12: Garmin writes `0` where a device reported no power at all and `--` where the column
     * does not apply. Both mean "unknown" — a stored 0 W would drag every average down and would
     * make a no-power ride look like a measured one.
     */
    @Test
    fun bike07_csv_zero_power_is_null() {
        val text = buildString {
            appendLine(
                "Aktivitätstyp,Datum,Zeit,Ø Leistung,Max. Leistung," +
                    "Normalized Power® (NP®),Ø Trittfrequenz",
            )
            appendLine("Radfahren,2026-02-05 16:25:06,01:00:12,0,0,0,0")
            appendLine("Radfahren,2026-02-06 16:25:06,01:00:12,--,--,--,--")
            appendLine("Radfahren,2026-02-07 16:25:06,01:00:12,166,310,167,91")
        }

        val rows = parser.parse(text).rows

        assertThat(rows).hasSize(3)
        assertThat(rows[0].avgPowerW).isNull()
        assertThat(rows[0].maxPowerW).isNull()
        assertThat(rows[0].normalizedPowerW).isNull()
        assertThat(rows[0].avgCadenceSpm).isNull()
        assertThat(rows[1].avgPowerW).isNull()
        assertThat(rows[1].normalizedPowerW).isNull()
        assertThat(rows[1].avgCadenceSpm).isNull()
        // The real values are untouched by the guard.
        assertThat(rows[2].avgPowerW).isEqualTo(166)
        assertThat(rows[2].normalizedPowerW).isEqualTo(167)
        assertThat(rows[2].avgCadenceSpm).isEqualTo(91.0)
    }

    @Test
    fun csv10_fractional_seconds_duration() {
        assertThat(parseDuration("00:09:53.7")).isEqualTo(593)
        assertThat(parseDuration("00:00:02.6")).isEqualTo(2)
        assertThat(parseDuration("1:05:30.9")).isEqualTo(3_930)

        // The 4 Aug ride is written "00:09:53.7" in the owner's export.
        val ride = parse(OWNER_FIXTURE).rows[17]
        assertThat(ride.durationSec).isEqualTo(593)
        assertThat(ride.distanceMeters).isEqualTo(3_060.0)
    }

    /** Every German activity type the owner's export contains has to map (case/diacritic-blind). */
    @Test
    fun csv11_german_activity_types_map() {
        val expected = mapOf(
            "Laufen" to SportType.RUN_OUTDOOR,
            "Laufbandtraining" to SportType.RUN_TREADMILL,
            "Fußball" to SportType.SOCCER_TRAINING,
            "Radfahren" to SportType.CYCLING,
            "Virtuelles Radfahren" to SportType.CYCLING_INDOOR,
            "Indoor-Radfahren" to SportType.CYCLING_INDOOR,
            "Krafttraining" to SportType.STRENGTH,
            "HIIT" to SportType.HIIT,
            "Gehen" to SportType.WALK,
            "Yoga" to SportType.MOBILITY,
            "Seilspringen" to SportType.HIIT,
            "Sonstige" to SportType.OTHER,
        )
        expected.forEach { (raw, sport) ->
            assertThat(GarminActivityTypeMap.toSportType(raw)).isEqualTo(sport)
            assertThat(GarminActivityTypeMap.toSportType(raw.uppercase())).isEqualTo(sport)
        }
        assertThat(GarminActivityTypeMap.toSportType("FUSSBALL")).isEqualTo(SportType.SOCCER_TRAINING)
    }

    /**
     * The owner's whole export (600 rows, 2023-01-21 … 2026-09-09) when it is still on this
     * machine. Skipped elsewhere — the file is the owner's data, not a committed fixture.
     */
    @Test
    fun csv12_owner_full_export_parses_without_errors() {
        val file = File(OWNER_EXPORT)
        assumeTrue("No owner export at $OWNER_EXPORT", file.isFile)

        val result = parser.parse(file.readText())

        assertThat(result.errors).isEmpty()
        assertThat(result.rows).hasSize(600)
        assertThat(result.rows.count { it.sportType == SportType.OTHER }).isEqualTo(38)
        assertThat(result.rows.none { it.distanceMeters?.let { d -> d > 200_000.0 } == true }).isTrue()
    }

    @Test
    fun a_row_becomes_a_csv_import_ingest_item() {
        val row = parse("garmin_en").rows.first()

        val item = parser.toIngestItem(row, nowMillis = 1_800_000_000_000L)

        assertThat(item.record.source).isEqualTo(ActivitySource.CSV_IMPORT)
        assertThat(item.record.externalId).isEqualTo(row.externalId)
        assertThat(item.record.payloadJson).contains("\"avgHr\":152")
        assertThat(item.session.sportType).isEqualTo(SportType.RUN_OUTDOOR)
        assertThat(item.session.durationSec).isEqualTo(1_500)
        assertThat(item.session.endAtMillis).isEqualTo(RUN_START + 1_500_000L)
        assertThat(item.session.dedupeBucket).isEqualTo("RUN|${RUN_START / 300_000L}")
        assertThat(item.session.hasStreams).isFalse()
    }

    @Test
    fun a_file_without_a_date_column_reports_one_error_and_no_rows() {
        val result = parser.parse("Activity Type,Title\nRunning,Nope\n")

        assertThat(result.rows).isEmpty()
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors.single()).contains("No date column")
    }

    @Test
    fun an_unparseable_date_fails_only_its_own_row() {
        val text = loadCsvFixture("garmin_en").replace("2026-05-11 17:30:00", "not-a-date")

        val result = parser.parse(text)

        assertThat(result.rows).hasSize(4)
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors.single()).startsWith("Row 3:")
    }

    private companion object {
        /** 2026-05-10T09:00 Europe/Berlin = 2026-05-10T07:00Z. */
        const val RUN_START = 1_778_396_400_000L

        /** The owner's real export, header row + the 20 rows of 3 Aug – 9 Sep 2026 (BUG-11). */
        const val OWNER_FIXTURE = "garmin_de_headers_en_numbers"
        const val OWNER_EXPORT = "/home/robert/Downloads/Activities.csv"
    }
}

/** Loads `app/src/test/resources/fixtures/csv/<name>.csv` off the classpath (rule R12). */
internal fun loadCsvFixture(name: String): String {
    val path = "fixtures/csv/$name.csv"
    val url = checkNotNull(GarminCsvParser::class.java.classLoader).getResource(path)
        ?: error("Missing CSV fixture on the classpath: $path")
    return url.readText()
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
