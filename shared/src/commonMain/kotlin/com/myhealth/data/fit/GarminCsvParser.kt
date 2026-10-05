package com.myhealth.data.fit

import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySourceRecord
import com.myhealth.domain.model.SportType
import com.myhealth.domain.repository.ActivityIngestItem
import com.myhealth.domain.util.epochMillisToDay
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One recognised row of a Garmin "Activities" CSV export (PLAN P7.3). */
data class GarminCsvActivity(
    /** §2.4: `sha256(rawRowText)`. */
    val externalId: String,
    val rawRow: String,
    val activityTypeRaw: String?,
    val sportType: SportType,
    val startAtMillis: Long,
    val title: String?,
    val distanceMeters: Double?,
    val calories: Double?,
    val durationSec: Int?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val elevationGainM: Double?,
    val aerobicTrainingEffect: Double?,
    /** Steps/min for runs and walks, revolutions per minute for rides (P12). */
    val avgCadenceSpm: Double? = null,
    val avgPowerW: Int? = null,
    val maxPowerW: Int? = null,
    val normalizedPowerW: Int? = null,
)

/** Rows that parsed, plus one message per row that did not (the import records them, P7.5). */
data class GarminCsvResult(
    val rows: List<GarminCsvActivity> = emptyList(),
    val errors: List<String> = emptyList(),
    val headers: List<String> = emptyList(),
)

@Serializable
internal data class GarminCsvPayload(
    val externalId: String,
    val activityType: String?,
    val title: String?,
    val startAtMillis: Long,
    val durationSec: Int?,
    val distanceMeters: Double?,
    val calories: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val elevationGainM: Double?,
    val aerobicTrainingEffect: Double?,
    /** Steps/min for runs and walks, revolutions per minute for rides (P12). */
    val avgCadenceSpm: Double? = null,
    val avgPowerW: Int? = null,
    val maxPowerW: Int? = null,
    val normalizedPowerW: Int? = null,
)

/**
 * Header-driven parser for Garmin's activity CSV export (PLAN P7.3).
 *
 * The export has **no official schema and varies by activity type, export vintage and UI
 * language**, so nothing here is positional: the header row is normalised into
 * `Map<canonicalName, columnIndex>` and only the recognised columns are read. Unknown columns are
 * ignored; recognised columns that are absent yield `null`.
 *
 * Tolerances: RFC-4180 quoting (commas and doubled quotes inside a field), `--` as "no value",
 * German decimal commas with thousands dots, `h:mm:ss`, `mm:ss` and `hh:mm:ss.s` durations, pace
 * columns (`5:00` min/km) as well as speed columns (km/h), distances in kilometres, and both
 * `dd.MM.yyyy HH:mm` and `yyyy-MM-dd HH:mm:ss` timestamps.
 *
 * The decimal convention is decided by [NumberStyle.detect] from the **numeric cells** of the
 * recognised numeric columns, never from the header language: the owner's export has German
 * headers and English numbers (BUG-11).
 */
class GarminCsvParser(
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val json: Json = Json { encodeDefaults = true },
) {

    fun parse(text: String): GarminCsvResult {
        val lines = text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return GarminCsvResult(errors = listOf("The file is empty."))

        val headerCells = splitCsvLine(lines.first())
        // Garmin's German export spells two different columns identically ("Ø Trittfrequenz" is
        // both the running and the cycling cadence), so a canonical name may own several indices;
        // `cell()` reads the first non-empty of them. `columns` keeps one index per name for the
        // number-format vote and the distance-unit sniff, which only need a representative column.
        val allColumns: Map<String, List<Int>> = headerCells.withIndex()
            .mapNotNull { (index, raw) -> canonicalHeader(raw)?.let { it to index } }
            .groupBy({ it.first }, { it.second })
        val columns = allColumns.mapValues { (_, indexes) -> indexes.last() }
        if (Columns.DATE !in columns) {
            return GarminCsvResult(
                headers = headerCells,
                errors = listOf("No date column found; recognised headers: ${columns.keys.sorted()}"),
            )
        }

        val body = lines.drop(1)
        val style = NumberStyle.detect(body, columns, headerCells)
        val rows = mutableListOf<GarminCsvActivity>()
        val errors = mutableListOf<String>()
        body.forEachIndexed { index, line ->
            when (val row = parseRow(line, allColumns, style, headerCells)) {
                is RowOutcome.Parsed -> rows += row.activity
                is RowOutcome.Failed -> errors += "Row ${index + 2}: ${row.reason}"
            }
        }
        return GarminCsvResult(rows = rows, errors = errors, headers = headerCells)
    }

    /** The `(source record, candidate session)` pair the ingestion pipeline consumes (§2.2.2). */
    fun toIngestItem(row: GarminCsvActivity, nowMillis: Long): ActivityIngestItem {
        val durationSec = row.durationSec ?: 0
        val group = row.sportType.group
        val payload = GarminCsvPayload(
            externalId = row.externalId,
            activityType = row.activityTypeRaw,
            title = row.title,
            startAtMillis = row.startAtMillis,
            durationSec = row.durationSec,
            distanceMeters = row.distanceMeters,
            calories = row.calories,
            avgHr = row.avgHr,
            maxHr = row.maxHr,
            avgSpeedMps = row.avgSpeedMps,
            maxSpeedMps = row.maxSpeedMps,
            elevationGainM = row.elevationGainM,
            aerobicTrainingEffect = row.aerobicTrainingEffect,
            avgCadenceSpm = row.avgCadenceSpm,
            avgPowerW = row.avgPowerW,
            maxPowerW = row.maxPowerW,
            normalizedPowerW = row.normalizedPowerW,
        )
        val session = ActivitySession(
            id = 0L,
            startAtMillis = row.startAtMillis,
            endAtMillis = row.startAtMillis + durationSec * 1000L,
            day = row.startAtMillis.epochMillisToDay(zone),
            sportType = row.sportType,
            sportGroup = group,
            title = row.title,
            durationSec = durationSec,
            elapsedSec = durationSec,
            distanceMeters = row.distanceMeters,
            activeEnergyKcal = row.calories,
            totalEnergyKcal = null,
            avgHr = row.avgHr,
            maxHr = row.maxHr,
            avgSpeedMps = row.avgSpeedMps
                ?: row.distanceMeters?.takeIf { durationSec > 0 }?.div(durationSec),
            maxSpeedMps = row.maxSpeedMps,
            avgCadenceSpm = row.avgCadenceSpm,
            elevationGainM = row.elevationGainM,
            avgPowerW = row.avgPowerW,
            maxPowerW = row.maxPowerW,
            normalizedPowerW = row.normalizedPowerW,
            trimp = null,
            loadMethod = null,
            rpe = null,
            note = null,
            primarySource = ActivitySource.CSV_IMPORT,
            mergedSources = listOf(ActivitySource.CSV_IMPORT),
            dedupeBucket = "$group|${row.startAtMillis / 300_000L}",
            userEditedFields = emptyList(),
            hasStreams = false,
            streams = null,
            laps = emptyList(),
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )
        return ActivityIngestItem(
            record = ActivitySourceRecord(
                id = 0L,
                activityId = null,
                source = ActivitySource.CSV_IMPORT,
                externalId = row.externalId,
                payloadJson = json.encodeToString(payload),
                receivedAtMillis = nowMillis,
            ),
            session = session,
        )
    }

    private sealed interface RowOutcome {
        data class Parsed(val activity: GarminCsvActivity) : RowOutcome
        data class Failed(val reason: String) : RowOutcome
    }

    private fun parseRow(
        line: String,
        columns: Map<String, List<Int>>,
        style: NumberStyle,
        headerCells: List<String>,
    ): RowOutcome {
        val cells = splitCsvLine(line)
        fun cell(name: String): String? = columns[name]
            .orEmpty()
            .firstNotNullOfOrNull { index ->
                cells.getOrNull(index)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it != NO_VALUE && it != "-" }
            }

        /** A power cell: Garmin writes `0` for "the device measured no power at all" (P12). */
        fun power(name: String): Int? =
            cell(name)?.let { style.parse(it) }?.toInt()?.takeIf { it > 0 }

        val startAtMillis = cell(Columns.DATE)?.let { parseDateTime(it, zone) }
            ?: return RowOutcome.Failed("unparseable date '${cell(Columns.DATE)}'")
        val typeRaw = cell(Columns.ACTIVITY_TYPE)
        val durationSec = cell(Columns.TIME)?.let { parseDuration(it, style) }
        val distanceRaw = cell(Columns.DISTANCE)?.let { style.parse(it) }
        return RowOutcome.Parsed(
            GarminCsvActivity(
                externalId = Sha256.hex(line),
                rawRow = line,
                activityTypeRaw = typeRaw,
                sportType = GarminActivityTypeMap.toSportType(typeRaw),
                startAtMillis = startAtMillis,
                title = cell(Columns.TITLE),
                distanceMeters = distanceRaw?.let { it * distanceScale(columns, headerCells) },
                calories = cell(Columns.CALORIES)?.let { style.parse(it) },
                durationSec = durationSec,
                avgHr = cell(Columns.AVG_HR)?.let { style.parse(it) }?.toInt(),
                maxHr = cell(Columns.MAX_HR)?.let { style.parse(it) }?.toInt(),
                avgSpeedMps = cell(Columns.AVG_SPEED)?.let { toMetresPerSecond(it, style) },
                maxSpeedMps = cell(Columns.MAX_SPEED)?.let { toMetresPerSecond(it, style) },
                elevationGainM = cell(Columns.ELEV_GAIN)?.let { style.parse(it) },
                aerobicTrainingEffect = cell(Columns.AEROBIC_TE)?.let { style.parse(it) },
                avgCadenceSpm = cell(Columns.AVG_CADENCE)?.let { style.parse(it) }?.takeIf { it > 0.0 },
                avgPowerW = power(Columns.AVG_POWER),
                maxPowerW = power(Columns.MAX_POWER),
                normalizedPowerW = power(Columns.NORMALIZED_POWER),
            ),
        )
    }

    /** Garmin exports distance in kilometres unless the header spells out metres. */
    private fun distanceScale(columns: Map<String, List<Int>>, headerCells: List<String>): Double {
        val raw = columns[Columns.DISTANCE]?.lastOrNull()
            ?.let { headerCells.getOrNull(it) }?.lowercase().orEmpty()
        return if ("(m)" in raw || " m)" in raw) 1.0 else 1000.0
    }

    /**
     * A speed column is km/h; a pace column is `m:ss` per kilometre. Both land in the same domain
     * field, and the two are told apart by the `:` a pace always has.
     */
    private fun toMetresPerSecond(value: String, style: NumberStyle): Double? = if (':' in value) {
        parseDuration(value, style)?.takeIf { it > 0 }?.let { 1000.0 / it }
    } else {
        style.parse(value)?.div(3.6)
    }

    private companion object {
        const val NO_VALUE = "--"
    }
}
