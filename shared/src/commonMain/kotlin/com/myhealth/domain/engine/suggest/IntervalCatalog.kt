package com.myhealth.domain.engine.suggest

import com.myhealth.domain.engine.running.DanielsPace

/** Which framing (and which target language) a template is written in (PLAN §3.11). */
enum class IntervalSport { RUN, BIKE }

/**
 * One step of an [IntervalTemplate] before any athlete's numbers are known (PLAN §3.11).
 *
 * A step is described either by [durationSec] or by [distanceMeters] — never both. [zone] is the
 * heart-rate zone it is meant to be run in and is always present, because a zone is the one target
 * that needs no VDOT and no FTP (selection rule 6). [pace] names the Daniels pace the work is
 * prescribed at when a VDOT exists, and [ftpLowPct]…[ftpHighPct] the share of FTP when one does.
 */
data class IntervalStepSpec(
    val durationSec: Int? = null,
    val distanceMeters: Double? = null,
    val zone: Int,
    val pace: DanielsPace? = null,
    val ftpLowPct: Double? = null,
    val ftpHighPct: Double? = null,
    val note: String? = null,
) {
    val isDistanceBased: Boolean get() = distanceMeters != null
}

/**
 * One interval session shape (PLAN §3.11): a work step, a recovery step and the rep range the
 * session may be built in. [IntervalBuilder] picks the template, fits the reps to the placed
 * minutes and turns the specs into a [com.myhealth.domain.model.WorkoutStructure].
 *
 * [innerReps] is the inner multiplier of `BIKE_30_30`'s `2 × (10 × 30/30)`; every other template
 * leaves it at 1. [maxWorkDurationSec] is only set for the continuous tempo run, whose single
 * "rep" stretches with the placed duration instead of multiplying.
 */
data class IntervalTemplate(
    val id: String,
    val sport: IntervalSport,
    val minReps: Int,
    val maxReps: Int,
    val work: IntervalStepSpec,
    val recovery: IntervalStepSpec?,
    val innerReps: Int = 1,
    val maxWorkDurationSec: Int? = null,
    /** `RUN_HILL_60`: the target is the hill, not a pace — `EFFORT` even when a VDOT exists. */
    val isEffort: Boolean = false,
) {
    val isRun: Boolean get() = sport == IntervalSport.RUN

    /** A single continuous block (`RUN_TEMPO_CONT`): no repeats, no recovery, just the work. */
    val isContinuous: Boolean get() = recovery == null

    val warmupSec: Int get() = if (isRun) IntervalCatalog.RUN_WARMUP_SEC else IntervalCatalog.BIKE_WARMUP_SEC

    val cooldownSec: Int get() = if (isRun) IntervalCatalog.RUN_COOLDOWN_SEC else IntervalCatalog.BIKE_COOLDOWN_SEC
}

/**
 * The eleven interval templates of PLAN §3.11 — the vocabulary that turns "55 minutes, hard" into
 * "5 × 1000 m at 3:54/km with 2:00 jog".
 *
 * | id | shape | target |
 * |---|---|---|
 * | `RUN_400_R` | 8–12 × 400 m, 400 m jog | R pace ± 2 %, Z5 |
 * | `RUN_800_I` | 4–8 × 800 m, 90 s jog | I pace ± 2 %, Z5 |
 * | `RUN_1000_I` | 4–6 × 1000 m, 2:00 jog | I pace ± 2 %, Z5 |
 * | `RUN_4X4` | 3–5 × 4 min, 3 min jog | Z4–Z5 |
 * | `RUN_HILL_60` | 8–12 × 60 s hill, jog down | Z5, effort |
 * | `RUN_CRUISE_T` | 3–5 × 8 min, 2 min jog | T pace ± 2 %, Z4 |
 * | `RUN_TEMPO_CONT` | 1 × 20–35 min continuous | T pace ± 2 %, Z4 |
 * | `BIKE_4X8_FTP` | 3–5 × 8 min, 4 min easy | 95–105 % FTP, Z4 |
 * | `BIKE_5X3_VO2` | 4–6 × 3 min, 3 min easy | 110–120 % FTP, Z5 |
 * | `BIKE_30_30` | 2–3 × (10 × 30 s / 30 s) | 130 % / 50 % FTP |
 * | `BIKE_2X20_SST` | 2–3 × 20 min, 5 min easy | 88–94 % FTP, Z3 |
 *
 * Every structure is framed by a warm-up (15 min run / 10 min ride) and a cool-down (10 min / 5
 * min). §3.11 gives the warm-up as "Z1–Z2": a [com.myhealth.domain.model.WorkoutStep] carries a
 * single zone, so the lower bound is stored and the range lives in the step's note.
 *
 * `RUN_800_I` is in the catalog but is never chosen by [IntervalBuilder]'s seven rules (§3.11's
 * selection table jumps from 400 m to 1000 m); it exists for the manual picker of P14.6.
 */
object IntervalCatalog {

    /** §3.11's framing: 15 min of warm-up and 10 min of cool-down around every run session. */
    const val RUN_WARMUP_SEC: Int = 900
    const val RUN_COOLDOWN_SEC: Int = 600

    /** …and 10 / 5 minutes around every ride. */
    const val BIKE_WARMUP_SEC: Int = 600
    const val BIKE_COOLDOWN_SEC: Int = 300

    /** The `± 2 %` of every pace target in the table above. */
    const val PACE_TOLERANCE: Double = 0.02

    /** `RUN_TEMPO_CONT` stretches between these two, instead of adding reps. */
    const val TEMPO_MIN_SEC: Int = 1_200
    const val TEMPO_MAX_SEC: Int = 2_100

    private val JOG = IntervalStepSpec(zone = 1, note = "Easy jog")

    val RUN_400_R: IntervalTemplate = IntervalTemplate(
        id = "RUN_400_R",
        sport = IntervalSport.RUN,
        minReps = 8,
        maxReps = 12,
        work = IntervalStepSpec(distanceMeters = 400.0, zone = 5, pace = DanielsPace.REPETITION),
        recovery = JOG.copy(distanceMeters = 400.0),
    )

    val RUN_800_I: IntervalTemplate = IntervalTemplate(
        id = "RUN_800_I",
        sport = IntervalSport.RUN,
        minReps = 4,
        maxReps = 8,
        work = IntervalStepSpec(distanceMeters = 800.0, zone = 5, pace = DanielsPace.INTERVAL),
        recovery = JOG.copy(durationSec = 90),
    )

    val RUN_1000_I: IntervalTemplate = IntervalTemplate(
        id = "RUN_1000_I",
        sport = IntervalSport.RUN,
        minReps = 4,
        maxReps = 6,
        work = IntervalStepSpec(distanceMeters = 1_000.0, zone = 5, pace = DanielsPace.INTERVAL),
        recovery = JOG.copy(durationSec = 120),
    )

    val RUN_4X4: IntervalTemplate = IntervalTemplate(
        id = "RUN_4X4",
        sport = IntervalSport.RUN,
        minReps = 3,
        maxReps = 5,
        work = IntervalStepSpec(durationSec = 240, zone = 5, note = "Hard, Z4–Z5"),
        recovery = JOG.copy(durationSec = 180),
    )

    val RUN_HILL_60: IntervalTemplate = IntervalTemplate(
        id = "RUN_HILL_60",
        sport = IntervalSport.RUN,
        minReps = 8,
        maxReps = 12,
        work = IntervalStepSpec(durationSec = 60, zone = 5, note = "Strong hill, by effort"),
        recovery = JOG.copy(durationSec = 60, note = "Jog down"),
        isEffort = true,
    )

    val RUN_CRUISE_T: IntervalTemplate = IntervalTemplate(
        id = "RUN_CRUISE_T",
        sport = IntervalSport.RUN,
        minReps = 3,
        maxReps = 5,
        work = IntervalStepSpec(durationSec = 480, zone = 4, pace = DanielsPace.THRESHOLD),
        recovery = JOG.copy(durationSec = 120),
    )

    val RUN_TEMPO_CONT: IntervalTemplate = IntervalTemplate(
        id = "RUN_TEMPO_CONT",
        sport = IntervalSport.RUN,
        minReps = 1,
        maxReps = 1,
        work = IntervalStepSpec(durationSec = TEMPO_MIN_SEC, zone = 4, pace = DanielsPace.THRESHOLD),
        recovery = null,
        maxWorkDurationSec = TEMPO_MAX_SEC,
    )

    val BIKE_4X8_FTP: IntervalTemplate = IntervalTemplate(
        id = "BIKE_4X8_FTP",
        sport = IntervalSport.BIKE,
        minReps = 3,
        maxReps = 5,
        work = IntervalStepSpec(durationSec = 480, zone = 4, ftpLowPct = 0.95, ftpHighPct = 1.05),
        recovery = IntervalStepSpec(durationSec = 240, zone = 1, note = "Easy spinning"),
    )

    val BIKE_5X3_VO2: IntervalTemplate = IntervalTemplate(
        id = "BIKE_5X3_VO2",
        sport = IntervalSport.BIKE,
        minReps = 4,
        maxReps = 6,
        work = IntervalStepSpec(durationSec = 180, zone = 5, ftpLowPct = 1.10, ftpHighPct = 1.20),
        recovery = IntervalStepSpec(durationSec = 180, zone = 1, note = "Easy spinning"),
    )

    val BIKE_30_30: IntervalTemplate = IntervalTemplate(
        id = "BIKE_30_30",
        sport = IntervalSport.BIKE,
        minReps = 2,
        maxReps = 3,
        work = IntervalStepSpec(durationSec = 30, zone = 5, ftpLowPct = 1.30, ftpHighPct = 1.30),
        recovery = IntervalStepSpec(durationSec = 30, zone = 1, ftpLowPct = 0.50, ftpHighPct = 0.50),
        innerReps = 10,
    )

    val BIKE_2X20_SST: IntervalTemplate = IntervalTemplate(
        id = "BIKE_2X20_SST",
        sport = IntervalSport.BIKE,
        minReps = 2,
        maxReps = 3,
        work = IntervalStepSpec(durationSec = 1_200, zone = 3, ftpLowPct = 0.88, ftpHighPct = 0.94),
        recovery = IntervalStepSpec(durationSec = 300, zone = 1, note = "Easy spinning"),
    )

    /** The eleven templates, in the order of §3.11's table. */
    val ALL: List<IntervalTemplate> = listOf(
        RUN_400_R,
        RUN_800_I,
        RUN_1000_I,
        RUN_4X4,
        RUN_HILL_60,
        RUN_CRUISE_T,
        RUN_TEMPO_CONT,
        BIKE_4X8_FTP,
        BIKE_5X3_VO2,
        BIKE_30_30,
        BIKE_2X20_SST,
    )

    private val byId: Map<String, IntervalTemplate> = ALL.associateBy { it.id }

    fun byId(id: String?): IntervalTemplate? = id?.let { byId[it] }
}
