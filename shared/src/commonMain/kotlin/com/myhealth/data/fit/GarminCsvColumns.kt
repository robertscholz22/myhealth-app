package com.myhealth.data.fit

import com.myhealth.domain.model.SportType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * The vocabulary and cell grammar [GarminCsvParser] reads Garmin's activity CSV with (PLAN P7.3).
 *
 * Kept apart from the parser itself because it is the part that grows: every new export vintage or
 * UI language adds header spellings and activity-type names, none of which change the parsing
 * algorithm.
 */
internal object Columns {
    const val ACTIVITY_TYPE = "activity type"
    const val DATE = "date"
    const val TITLE = "title"
    const val DISTANCE = "distance"
    const val CALORIES = "calories"
    const val TIME = "time"
    const val AVG_HR = "avg hr"
    const val MAX_HR = "max hr"
    const val AVG_SPEED = "avg speed"
    const val MAX_SPEED = "max speed"
    const val ELEV_GAIN = "elev gain"
    const val AEROBIC_TE = "aerobic te"
    const val AVG_POWER = "avg power"
    const val MAX_POWER = "max power"
    const val NORMALIZED_POWER = "normalized power"

    /**
     * Average cadence: steps/min for a run or walk, **revolutions per minute for a ride** (P12).
     * Garmin's German export spells the running and the cycling column identically
     * ("Ø Trittfrequenz" twice), so both map here and the parser takes the first non-empty cell —
     * a row only ever fills one of the two.
     */
    const val AVG_CADENCE = "avg cadence"

    /** The columns whose cells carry free-form numbers, i.e. the evidence [NumberStyle] reads. */
    val NUMERIC: List<String> = listOf(DISTANCE, CALORIES, AVG_SPEED, MAX_SPEED, ELEV_GAIN)
}

/** English header spellings, normalised by [canonicalHeader]. */
internal val ENGLISH_HEADER_ALIASES: Map<String, String> = mapOf(
    "activity type" to Columns.ACTIVITY_TYPE,
    "date" to Columns.DATE, "start time" to Columns.DATE,
    "title" to Columns.TITLE, "activity name" to Columns.TITLE,
    "distance" to Columns.DISTANCE,
    "calories" to Columns.CALORIES,
    "time" to Columns.TIME, "moving time" to Columns.TIME, "duration" to Columns.TIME,
    "avg hr" to Columns.AVG_HR, "average hr" to Columns.AVG_HR,
    "max hr" to Columns.MAX_HR,
    "avg speed" to Columns.AVG_SPEED, "avg pace" to Columns.AVG_SPEED,
    "max speed" to Columns.MAX_SPEED, "best pace" to Columns.MAX_SPEED,
    "elev gain" to Columns.ELEV_GAIN, "total ascent" to Columns.ELEV_GAIN,
    "aerobic te" to Columns.AEROBIC_TE,
    // P12 power and cadence. `normalizeHeader` strips the `(NP®)` suffix and the ® glyphs, so
    // "Normalized Power® (NP®)" arrives here as "normalized power".
    "avg power" to Columns.AVG_POWER,
    "max power" to Columns.MAX_POWER,
    "normalized power" to Columns.NORMALIZED_POWER,
    "avg bike cadence" to Columns.AVG_CADENCE, "avg run cadence" to Columns.AVG_CADENCE,
)

/**
 * German header spellings. `Ø` is expanded to `durchschnittliche` by [canonicalHeader], so the
 * averages of a German export arrive here spelled out ("Ø Herzfrequenz" → "durchschnittliche
 * herzfrequenz"). Which of the two maps a file's headers come from is only ever the *tie-breaker*
 * for the number format (see [NumberStyle.detect]) — never the decision itself (BUG-11).
 */
internal val GERMAN_HEADER_ALIASES: Map<String, String> = mapOf(
    "aktivitatstyp" to Columns.ACTIVITY_TYPE, "typ" to Columns.ACTIVITY_TYPE,
    "datum" to Columns.DATE,
    "titel" to Columns.TITLE,
    "distanz" to Columns.DISTANCE, "strecke" to Columns.DISTANCE,
    "kalorien" to Columns.CALORIES,
    "zeit" to Columns.TIME, "dauer" to Columns.TIME,
    "durchschnittliche hf" to Columns.AVG_HR, "hf" to Columns.AVG_HR,
    "durchschnittliche herzfrequenz" to Columns.AVG_HR, "herzfrequenz" to Columns.AVG_HR,
    "maximale hf" to Columns.MAX_HR, "maximale herzfrequenz" to Columns.MAX_HR,
    "durchschnittliche geschwindigkeit" to Columns.AVG_SPEED,
    "durchschnittspace" to Columns.AVG_SPEED, "durchschnittliche pace" to Columns.AVG_SPEED,
    "maximale geschwindigkeit" to Columns.MAX_SPEED, "beste pace" to Columns.MAX_SPEED,
    "anstieg gesamt" to Columns.ELEV_GAIN, "gesamtanstieg" to Columns.ELEV_GAIN,
    "aerober te" to Columns.AEROBIC_TE,
    // P12. "Ø Leistung" → "durchschnittliche leistung"; "Max. Leistung" → "max leistung";
    // "Ø Trittfrequenz" → "durchschnittliche trittfrequenz" (both the bike and the run column).
    "durchschnittliche leistung" to Columns.AVG_POWER,
    "max leistung" to Columns.MAX_POWER, "maximale leistung" to Columns.MAX_POWER,
    "durchschnittliche trittfrequenz" to Columns.AVG_CADENCE,
    "durchschnittliche laufschrittfrequenz" to Columns.AVG_CADENCE,
)

/** Every spelling of a recognised column seen in Garmin exports, normalised. */
internal val HEADER_ALIASES: Map<String, String> = ENGLISH_HEADER_ALIASES + GERMAN_HEADER_ALIASES

/**
 * Lowercases, strips the unit suffix in parentheses, folds the German umlauts and the `Ø` prefix
 * Garmin uses for averages, and collapses everything that is not a letter or digit to one space.
 */
internal fun canonicalHeader(raw: String): String? = HEADER_ALIASES[normalizeHeader(raw)]

internal fun normalizeHeader(raw: String): String = foldGerman(raw)
    .replace("ø", "durchschnittliche ")
    .substringBefore('(')
    .map { if (it.isLetterOrDigit()) it else ' ' }
    .joinToString("")
    .split(' ')
    .filter { it.isNotEmpty() }
    .joinToString(" ")

/** Lowercase plus the umlaut/ß folding every German lookup in this file shares. */
private fun foldGerman(raw: String): String = raw.lowercase()
    .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")

// ---- cell parsing ---------------------------------------------------------------------------

/** Decimal separator convention of one file, inferred from the numbers the file actually holds. */
enum class NumberStyle {
    ENGLISH,
    GERMAN,
    ;

    fun parse(raw: String): Double? {
        val cleaned = raw.trim().removeSuffix("%").filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
        if (cleaned.isEmpty()) return null
        val normalized = when (this) {
            ENGLISH -> cleaned.replace(",", "")
            GERMAN -> cleaned.replace(".", "").replace(',', '.')
        }
        return normalized.toDoubleOrNull()
    }

    companion object {
        /** `1,057` / `12,345,678` — a comma followed by exactly three digits is a thousands group. */
        private val ENGLISH_THOUSANDS = Regex("""^\d{1,3}(,\d{3})+$""")

        /** `1.057` — the same shape with the separators swapped. */
        private val GERMAN_THOUSANDS = Regex("""^\d{1,3}(\.\d{3})+$""")

        /** `7.20`, `1,057.5` — a dot decimal, with or without comma thousands groups. */
        private val ENGLISH_DECIMAL = Regex("""^\d{1,3}(,\d{3})*\.\d+$""")

        /** `10,52`, `1.057,5` — a comma decimal, with or without dot thousands groups. */
        private val GERMAN_DECIMAL = Regex("""^\d{1,3}(\.\d{3})*,\d+$""")

        /**
         * The number format one cell is evidence for, or `null` when it is evidence for neither —
         * a plain integer (`452`), a pace (`4:23`), `--`, or any shape outside both grammars.
         *
         * Where the two grammars genuinely overlap (`1,057` is an English thousands group and a
         * German three-decimal number) the thousands reading wins, symmetrically for both
         * languages: three digits behind the separator is how a grouped number looks.
         */
        internal fun classify(raw: String?): NumberStyle? = when (val value = raw?.trim()) {
            null -> null
            else -> when {
                ENGLISH_THOUSANDS.matches(value) -> ENGLISH
                GERMAN_THOUSANDS.matches(value) -> GERMAN
                ENGLISH_DECIMAL.matches(value) -> ENGLISH
                GERMAN_DECIMAL.matches(value) -> GERMAN
                else -> null
            }
        }

        /**
         * BUG-11: the number format is decided by the **numbers**, never by the header language.
         * The owner's real export has German headers and English numbers ("7.20", "1,057"), which
         * the old "any German-looking cell wins" rule read as German — every distance came out
         * ×100. Each cell of the recognised numeric columns ([Columns.NUMERIC]) votes through
         * [classify]; the majority wins and only an exact tie falls back to the headers.
         */
        fun detect(
            rows: List<String>,
            columns: Map<String, Int>,
            headerCells: List<String>,
        ): NumberStyle {
            val indexes = Columns.NUMERIC.mapNotNull { columns[it] }
            var english = 0
            var german = 0
            if (indexes.isNotEmpty()) {
                for (line in rows) {
                    val cells = splitCsvLine(line)
                    for (index in indexes) {
                        when (classify(cells.getOrNull(index))) {
                            ENGLISH -> english++
                            GERMAN -> german++
                            null -> Unit
                        }
                    }
                }
            }
            return when {
                english > german -> ENGLISH
                german > english -> GERMAN
                else -> headerStyleGuess(headerCells)
            }
        }

        /** The tie-breaker only: a header row written in German suggests German numbers. */
        fun headerStyleGuess(headerCells: List<String>): NumberStyle {
            val normalized = headerCells.map { normalizeHeader(it) }
            val german = normalized.count { it in GERMAN_HEADER_ALIASES }
            val english = normalized.count { it in ENGLISH_HEADER_ALIASES }
            return if (german > english) GERMAN else ENGLISH
        }
    }
}

/** `h:mm:ss`, `mm:ss` or plain seconds; fractional seconds (`00:09:53.7`) are truncated. */
fun parseDuration(raw: String, style: NumberStyle = NumberStyle.ENGLISH): Int? {
    val value = raw.trim()
    if (':' !in value) return style.parse(value)?.toInt()
    val parts = value.split(':')
    if (parts.size !in 2..3) return null
    val numbers = parts.map { part ->
        part.replace(',', '.').toDoubleOrNull() ?: return null
    }
    val seconds = when (numbers.size) {
        2 -> numbers[0] * 60 + numbers[1]
        else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
    }
    return seconds.toInt()
}

/**
 * The date-time layouts Garmin exports use, tried in this order. Each was a `java.time`
 * `DateTimeFormatter.ofPattern` until P20.2: `(y, M, d)` are the regex groups holding year, month
 * and day; every layout has hour and minute after them and optionally seconds.
 */
private class DateLayout(pattern: String, val y: Int, val m: Int, val d: Int) {
    val regex = Regex(pattern)
}

private val DATE_LAYOUTS: List<DateLayout> = listOf(
    DateLayout("""(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})""", 1, 2, 3), // yyyy-MM-dd HH:mm:ss
    DateLayout("""(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})()""", 1, 2, 3), // yyyy-MM-dd HH:mm
    DateLayout("""(\d{2})\.(\d{2})\.(\d{4}) (\d{2}):(\d{2}):(\d{2})""", 3, 2, 1), // dd.MM.yyyy HH:mm:ss
    DateLayout("""(\d{2})\.(\d{2})\.(\d{4}) (\d{2}):(\d{2})()""", 3, 2, 1), // dd.MM.yyyy HH:mm
    DateLayout("""(\d{2})/(\d{2})/(\d{4}) (\d{2}):(\d{2}):(\d{2})""", 3, 2, 1), // dd/MM/yyyy HH:mm:ss
    DateLayout("""(\d{2})/(\d{2})/(\d{4}) (\d{2}):(\d{2}):(\d{2})""", 3, 1, 2), // MM/dd/yyyy HH:mm:ss
    DateLayout("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""", 1, 2, 3), // yyyy-MM-dd'T'HH:mm:ss
)

private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")

/** Garmin writes wall-clock local time with no offset, so the app's zone supplies it. */
fun parseDateTime(raw: String, zone: TimeZone): Long? {
    val value = raw.trim().removeSuffix("Z")
    for (layout in DATE_LAYOUTS) {
        val groups = layout.regex.matchEntire(value)?.groupValues ?: continue
        val local = resolveSmart(
            year = groups[layout.y].toInt(),
            month = groups[layout.m].toInt(),
            day = groups[layout.d].toInt(),
            hour = groups[4].toInt(),
            minute = groups[5].toInt(),
            second = groups[6].ifEmpty { "0" }.toInt(),
        ) ?: continue
        return local.toInstant(zone).toEpochMilliseconds()
    }
    // ISO date alone, resolved strictly as `LocalDate.parse` does.
    val groups = ISO_DATE.matchEntire(value)?.groupValues ?: return null
    val (year, month, day) = groups.drop(1).map { it.toInt() }
    if (month !in 1..12 || day !in 1..daysIn(year, month)) return null
    return LocalDate(year, month, day).atStartOfDayIn(zone).toEpochMilliseconds()
}

/**
 * `java.time`'s SMART resolution, which the `ofPattern` formatters used: a day of 29–31 past the
 * month's end is moved back to its last day, `24:00:00` is midnight of the next day, every other
 * out-of-range field rejects the layout.
 */
private fun resolveSmart(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): LocalDateTime? {
    if (month !in 1..12 || day !in 1..31 || minute !in 0..59 || second !in 0..59) return null
    val date = LocalDate(year, month, minOf(day, daysIn(year, month)))
    return when (hour) {
        in 0..23 -> LocalDateTime(date, LocalTime(hour, minute, second))
        24 -> if (minute == 0 && second == 0) LocalDateTime(date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0)) else null
        else -> null
    }
}

private fun daysIn(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** RFC-4180 splitting: quoted fields may contain commas, and `""` is a literal quote. */
internal fun splitCsvLine(line: String): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            inQuotes && char == '"' && line.getOrNull(index + 1) == '"' -> {
                current.append('"')
                index++
            }
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                cells += current.toString()
                current.setLength(0)
            }
            else -> current.append(char)
        }
        index++
    }
    cells += current.toString()
    return cells
}

/**
 * Garmin's activity-type strings (English and German UI) → [SportType]; unknown → `OTHER`.
 *
 * Lookup is case- and diacritic-insensitive and ignores punctuation, so "Fußball", "FUSSBALL" and
 * "Indoor-Radfahren" all resolve ("indoor radfahren" is the normalised key).
 */
object GarminActivityTypeMap {

    fun toSportType(raw: String?): SportType {
        val key = raw?.let { normalizeHeader(it) }.orEmpty()
        if (key.isEmpty()) return SportType.OTHER
        EXACT[key]?.let { return it }
        return CONTAINS.firstOrNull { (needle, _) -> needle in key }?.second ?: SportType.OTHER
    }

    private val EXACT: Map<String, SportType> = mapOf(
        "running" to SportType.RUN_OUTDOOR, "laufen" to SportType.RUN_OUTDOOR,
        "street running" to SportType.RUN_OUTDOOR, "laufen im freien" to SportType.RUN_OUTDOOR,
        "treadmill running" to SportType.RUN_TREADMILL, "laufband" to SportType.RUN_TREADMILL,
        "laufbandtraining" to SportType.RUN_TREADMILL, "indoor running" to SportType.RUN_TREADMILL,
        "trail running" to SportType.RUN_TRAIL, "traillauf" to SportType.RUN_TRAIL,
        "track running" to SportType.RUN_TRACK, "bahnlauf" to SportType.RUN_TRACK,
        "cycling" to SportType.CYCLING, "radfahren" to SportType.CYCLING,
        "road cycling" to SportType.CYCLING, "gravel unpaved cycling" to SportType.CYCLING,
        "indoor cycling" to SportType.CYCLING_INDOOR, "indoor radfahren" to SportType.CYCLING_INDOOR,
        "virtual cycling" to SportType.CYCLING_INDOOR,
        "virtuelles radfahren" to SportType.CYCLING_INDOOR,
        "walking" to SportType.WALK, "gehen" to SportType.WALK,
        "hiking" to SportType.HIKE, "wandern" to SportType.HIKE,
        "soccer" to SportType.SOCCER_TRAINING, "football" to SportType.SOCCER_TRAINING,
        "fussball" to SportType.SOCCER_TRAINING,
        "strength training" to SportType.STRENGTH, "krafttraining" to SportType.STRENGTH,
        "cardio" to SportType.HIIT, "hiit" to SportType.HIIT,
        "jump rope" to SportType.HIIT, "seilspringen" to SportType.HIIT,
        "yoga" to SportType.MOBILITY, "pilates" to SportType.MOBILITY,
        "breathwork" to SportType.MOBILITY, "stretching" to SportType.MOBILITY,
        "pool swim" to SportType.SWIM, "open water swimming" to SportType.SWIM,
        "schwimmbadschwimmen" to SportType.SWIM, "schwimmen" to SportType.SWIM,
        "rowing" to SportType.ROWING, "indoor rowing" to SportType.ROWING, "rudern" to SportType.ROWING,
        "other" to SportType.OTHER, "sonstige" to SportType.OTHER, "sonstiges" to SportType.OTHER,
    )

    /** Fallbacks for the long tail ("Virtual Running", "E-Bike Ride", …). */
    private val CONTAINS: List<Pair<String, SportType>> = listOf(
        "treadmill" to SportType.RUN_TREADMILL,
        "laufband" to SportType.RUN_TREADMILL,
        "trail" to SportType.RUN_TRAIL,
        "running" to SportType.RUN_OUTDOOR,
        "lauf" to SportType.RUN_OUTDOOR,
        "cycling" to SportType.CYCLING,
        "bike" to SportType.CYCLING,
        "rad" to SportType.CYCLING,
        "walk" to SportType.WALK,
        "swim" to SportType.SWIM,
        "strength" to SportType.STRENGTH,
        "rowing" to SportType.ROWING,
    )
}
