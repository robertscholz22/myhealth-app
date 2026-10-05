package com.myhealth.domain.engine.suggest

import com.myhealth.domain.engine.load.SessionZoneTargets
import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.engine.running.DanielsPace
import com.myhealth.domain.engine.running.DanielsPaces
import com.myhealth.domain.engine.running.PaceConfidence
import com.myhealth.domain.engine.running.PaceZoneBand
import com.myhealth.domain.engine.running.PaceZoneEngine
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.domain.model.WorkoutStructure
import kotlin.math.floor

/**
 * Turns a placed candidate into a structured workout (PLAN §3.11), deterministically, first match
 * wins:
 *
 * 1. only `INTERVAL_RUN`, `TEMPO_RUN`, `BIKE_INTERVALS` and `TRAINER_SESSION` get a structure —
 *    every other session type gets `null`, so no other output can change;
 * 2. the phase picks the template (`PEAK` → 400s for a 5 k goal else 1000s, `BUILD` → 1000s /
 *    `BIKE_4X8_FTP`, `BASE` → hills / cruise intervals, `IN_SEASON` → 4×4 / 30-30,
 *    `TAPER`/`RACE_WEEK` → the same choice with `reps = max(minReps, roundHalfUp(reps × 0.6))`,
 *    `RECOVERY_WEEK`/`OFF_SEASON` → nothing);
 * 3. a goal distance overrides it: `≥ 21 097 m` → cruise intervals, `≤ 5 000 m` in a peak/taper
 *    week → 400s;
 * 4. the reps fit the placed minutes: `clamp(floor(workBudget / repCycle), minReps, maxReps)`;
 * 5. `acwr > 1.30`, a `MODERATE`/`FATIGUED` recovery band or a starter week force `minReps`;
 * 6. no VDOT ⇒ no pace targets, no FTP ⇒ no power targets — the zone alone carries the session;
 * 7. the total work distance of a run never exceeds 6 000 m.
 *
 * Interpretation choices (§3.11 leaves each of them open):
 * - rule 2's `max(minReps, …)` floor is taken literally, so `RUN_1000_I`'s 5 reps shorten to 4,
 *   not to the 3 the `iv03` row quotes (recorded as a §3.11 note);
 * - `TAPER`/`RACE_WEEK` have no template of their own; they reuse the `PEAK` choice, which is the
 *   phase a taper follows;
 * - a **distance** rep cannot be written down without a pace to convert it, so an athlete with no
 *   VDOT and no measured band gets the time-based `RUN_4X4` instead of 400s/1000s — exactly what
 *   §3.11's catalog means by "`RUN_4X4`: `INTERVAL_RUN` without a VDOT";
 * - rule 7's 6 km cap is applied to distance-based work only; applying it to time-based work would
 *   cut 5 × 8 min of threshold running (≈ 9.4 km) down to three reps, which is not a cruise
 *   interval session any more, and could never be satisfied by the continuous tempo run at all;
 * - a measured [PaceZoneBand] replaces the Daniels anchor when it is really measured (`HIGH`,
 *   `MEDIUM` or `LOW`) **and** the zone's anchor pace is the one the template prescribes, so a
 *   measured Z5 never gets applied to `RUN_400_R`'s repetition pace.
 */
object IntervalBuilder {

    /** §3.11 rule 1: the only four session types that ever carry a structure. */
    val STRUCTURED_TYPES: Set<SessionType> = setOf(
        SessionType.INTERVAL_RUN,
        SessionType.TEMPO_RUN,
        SessionType.BIKE_INTERVALS,
        SessionType.TRAINER_SESSION,
    )

    /** §3.11 rule 2's taper multiplier. */
    const val TAPER_REP_FACTOR: Double = 0.6

    /** §3.11 rule 5: above this ACWR the session is built at its minimum. */
    const val MIN_REPS_ACWR: Double = 1.30

    /** §3.11 rule 7: a run session never prescribes more than this much work distance. */
    const val MAX_WORK_DISTANCE_M: Double = 6_000.0

    /** §3.11 rule 3's two goal-distance thresholds. */
    const val LONG_GOAL_M: Double = 21_097.0
    const val SHORT_GOAL_M: Double = 5_000.0

    /** The jog pace a distance-based recovery is timed at when the athlete has no numbers yet. */
    const val DEFAULT_JOG_SEC_PER_KM: Int = 360

    /** The Daniels pace §3.10.2 anchors each zone to; a measured band may only replace that one. */
    private val ZONE_ANCHOR: Map<Int, DanielsPace> = mapOf(
        2 to DanielsPace.EASY,
        3 to DanielsPace.MARATHON,
        4 to DanielsPace.THRESHOLD,
        5 to DanielsPace.INTERVAL,
    )

    private val MEASURED: Set<PaceConfidence> =
        setOf(PaceConfidence.HIGH, PaceConfidence.MEDIUM, PaceConfidence.LOW)

    /** The structure of [candidate], or `null` when this session type or phase gets none. */
    fun buildFor(candidate: Candidate, ctx: IntervalContext): WorkoutStructure? =
        plan(candidate, ctx)?.structure

    /** [buildFor] with the numbers behind it (what the rationale and the UI summary read). */
    fun plan(candidate: Candidate, ctx: IntervalContext): IntervalPlan? {
        if (candidate.sessionType !in STRUCTURED_TYPES) return null
        val chosen = templateFor(candidate.sessionType, ctx) ?: return null
        val template = substituteIfUnpaceable(chosen, ctx)

        val workPace = workPaceOf(template, ctx)
        val power = powerOf(template, ctx)
        val recoverySec = recoverySecOf(template, ctx)
        val budgetSec = candidate.minutes * SEC_PER_MIN - template.warmupSec - template.cooldownSec

        return if (template.isContinuous) {
            continuousPlan(template, ctx, budgetSec, workPace)
        } else {
            repeatedPlan(template, ctx, budgetSec, workPace, power, recoverySec)
        }
    }

    // ---- rules 2 + 3: which template ------------------------------------------------------------

    private fun templateFor(sessionType: SessionType, ctx: IntervalContext): IntervalTemplate? {
        if (ctx.phase == TrainingPhase.RECOVERY_WEEK || ctx.phase == TrainingPhase.OFF_SEASON) return null
        // 0.8.1: the side goal's weekly session is the 1000 m reps, whatever the long goal wants.
        if (sessionType == SessionType.INTERVAL_RUN && ctx.sideGoal != null) return IntervalCatalog.RUN_1000_I
        val base = when (sessionType) {
            SessionType.INTERVAL_RUN -> intervalRunTemplate(ctx)
            SessionType.TEMPO_RUN ->
                if (ctx.phase == TrainingPhase.BASE) IntervalCatalog.RUN_CRUISE_T
                else IntervalCatalog.RUN_TEMPO_CONT
            SessionType.BIKE_INTERVALS -> when (ctx.phase) {
                TrainingPhase.PEAK, TrainingPhase.TAPER, TrainingPhase.RACE_WEEK -> IntervalCatalog.BIKE_5X3_VO2
                TrainingPhase.IN_SEASON -> IntervalCatalog.BIKE_30_30
                else -> IntervalCatalog.BIKE_4X8_FTP
            }
            else -> IntervalCatalog.BIKE_2X20_SST
        }
        // Rule 3: a long goal wants threshold work whatever the phase says.
        val long = (ctx.goalDistanceMeters ?: 0.0) >= LONG_GOAL_M
        return if (base.isRun && long) IntervalCatalog.RUN_CRUISE_T else base
    }

    private fun intervalRunTemplate(ctx: IntervalContext): IntervalTemplate = when (ctx.phase) {
        TrainingPhase.PEAK, TrainingPhase.TAPER, TrainingPhase.RACE_WEEK ->
            if ((ctx.goalDistanceMeters ?: Double.MAX_VALUE) <= SHORT_GOAL_M) IntervalCatalog.RUN_400_R
            else IntervalCatalog.RUN_1000_I
        TrainingPhase.BASE -> IntervalCatalog.RUN_HILL_60
        TrainingPhase.IN_SEASON -> IntervalCatalog.RUN_4X4
        else -> IntervalCatalog.RUN_1000_I
    }

    /** A distance rep needs a pace to become seconds; without one the 4×4 stands in for it. */
    private fun substituteIfUnpaceable(
        template: IntervalTemplate,
        ctx: IntervalContext,
    ): IntervalTemplate =
        if (template.work.isDistanceBased && workPaceOf(template, ctx) == null) IntervalCatalog.RUN_4X4
        else template

    // ---- rules 4, 5, 7: how many reps -----------------------------------------------------------

    @Suppress("LongParameterList")
    private fun repeatedPlan(
        template: IntervalTemplate,
        ctx: IntervalContext,
        budgetSec: Int,
        workPace: Int?,
        power: Pair<Int, Int>?,
        recoverySec: Int,
    ): IntervalPlan {
        val workSec = workSecOf(template, workPace)
        val cycleSec = template.innerReps * (workSec + recoverySec)
        val fitted = if (cycleSec <= 0) template.minReps else floor(budgetSec.toDouble() / cycleSec).toInt()
        var reps = fitted.coerceIn(template.minReps, template.maxReps)
        if (forcesMinimum(ctx)) reps = template.minReps

        val beforeTaper = reps
        if (isTaper(ctx)) {
            reps = maxOf(template.minReps, TrimpDefaults.roundHalfUp(reps * TAPER_REP_FACTOR))
        }
        reps = capWorkDistance(template, reps)

        val structure = IntervalStructures.structureOf(
            template, reps, workSec, workPace, power, recoverySec,
            IntervalStructures.recoveryPowerOf(template, ctx),
        )
        return IntervalPlan(
            template = template,
            reps = reps,
            workSec = workSec,
            structure = structure,
            workPaceSecPerKm = workPace,
            workPowerLowW = power?.first,
            workPowerHighW = power?.second,
            recoverySec = recoverySec,
            shortenedForTaper = isTaper(ctx) && reps < beforeTaper,
            summary = IntervalStructures.summary(structure).orEmpty(),
            usesGoalPace = workPace != null && usesGoalPace(template, ctx),
        )
    }

    /** `RUN_TEMPO_CONT`: one block that stretches with the placed minutes instead of repeating. */
    private fun continuousPlan(
        template: IntervalTemplate,
        ctx: IntervalContext,
        budgetSec: Int,
        workPace: Int?,
    ): IntervalPlan {
        val min = template.work.durationSec ?: IntervalCatalog.TEMPO_MIN_SEC
        val max = template.maxWorkDurationSec ?: min
        var workSec = budgetSec.coerceIn(min, max)
        val before = workSec
        if (isTaper(ctx) || forcesMinimum(ctx)) {
            workSec = maxOf(min, TrimpDefaults.roundHalfUp(workSec * TAPER_REP_FACTOR))
        }
        val structure = IntervalStructures.structureOf(
            template, reps = 1, workSec = workSec, workPace = workPace, power = null, recoverySec = 0,
        )
        return IntervalPlan(
            template = template,
            reps = 1,
            workSec = workSec,
            structure = structure,
            workPaceSecPerKm = workPace,
            workPowerLowW = null,
            workPowerHighW = null,
            recoverySec = 0,
            shortenedForTaper = isTaper(ctx) && workSec < before,
            summary = IntervalStructures.summary(structure).orEmpty(),
            usesGoalPace = workPace != null && usesGoalPace(template, ctx),
        )
    }

    private fun isTaper(ctx: IntervalContext): Boolean =
        ctx.phase == TrainingPhase.TAPER || ctx.phase == TrainingPhase.RACE_WEEK

    /** §3.11 rule 5 — a loaded, tired or brand-new athlete gets the shortest version. */
    private fun forcesMinimum(ctx: IntervalContext): Boolean =
        (ctx.acwr ?: 0.0) > MIN_REPS_ACWR ||
            ctx.recoveryBand == RecoveryBand.MODERATE ||
            ctx.recoveryBand == RecoveryBand.FATIGUED ||
            ctx.isStarterWeek

    /** §3.11 rule 7 — drop reps until the work distance fits into 6 km (distance reps only). */
    private fun capWorkDistance(template: IntervalTemplate, reps: Int): Int {
        val distance = template.work.distanceMeters ?: return reps
        if (distance <= 0.0) return reps
        val maxReps = floor(MAX_WORK_DISTANCE_M / distance).toInt()
        return maxOf(1, minOf(reps, maxReps))
    }

    // ---- rule 6: the targets --------------------------------------------------------------------

    /** The work step's pace in s/km: the measured band when it measures this pace, else Daniels. */
    private fun workPaceOf(template: IntervalTemplate, ctx: IntervalContext): Int? {
        if (template.isEffort) return null
        val pace = template.work.pace ?: return null
        if (usesGoalPace(template, ctx)) return goalOf(template, ctx)?.goalPaceSecPerKm
        measuredCentre(template.work.zone, pace, ctx)?.let { return it }
        return ctx.vdot?.let { DanielsPaces.secPerKm(it, pace) }
    }

    /** P19: a goal within 3 % in a build/peak/taper week replaces the race-distance template's pace. */
    private fun usesGoalPace(template: IntervalTemplate, ctx: IntervalContext): Boolean =
        if (isSideGoalSession(template, ctx)) {
            GoalRules.sideGoalUsesGoalPace(ctx.sideGoal, template.id)
        } else {
            GoalRules.usesGoalPace(ctx.paceGoal, template.id, ctx.phase)
        }

    /** The goal a template's goal pace comes from: the side goal for its own session, else the main one. */
    private fun goalOf(template: IntervalTemplate, ctx: IntervalContext): PaceGoal? =
        if (isSideGoalSession(template, ctx)) ctx.sideGoal else ctx.paceGoal

    /** A side goal only exists next to a long main goal, whose own runs never use `RUN_1000_I`. */
    private fun isSideGoalSession(template: IntervalTemplate, ctx: IntervalContext): Boolean =
        ctx.sideGoal != null && template.id == GoalRules.SIDE_GOAL_TEMPLATE_ID

    private fun measuredCentre(zone: Int, pace: DanielsPace, ctx: IntervalContext): Int? {
        if (ZONE_ANCHOR[zone] != pace) return null
        val band = ctx.paceBands.firstOrNull { it.zone == zone } ?: return null
        return if (band.confidence in MEASURED) band.centreSecPerKm else null
    }

    /** The work step's watts, `null` without an FTP (rule 6). */
    private fun powerOf(template: IntervalTemplate, ctx: IntervalContext): Pair<Int, Int>? {
        val ftp = ctx.ftpWatts ?: return null
        val low = template.work.ftpLowPct ?: return null
        val high = template.work.ftpHighPct ?: low
        return TrimpDefaults.roundHalfUp(ftp * low) to TrimpDefaults.roundHalfUp(ftp * high)
    }

    private fun workSecOf(template: IntervalTemplate, workPace: Int?): Int {
        template.work.durationSec?.let { return it }
        val distance = template.work.distanceMeters ?: return 0
        val pace = workPace ?: DEFAULT_JOG_SEC_PER_KM
        return TrimpDefaults.roundHalfUp(distance / METERS_PER_KM * pace)
    }

    private fun recoverySecOf(template: IntervalTemplate, ctx: IntervalContext): Int {
        val recovery = template.recovery ?: return 0
        recovery.durationSec?.let { return it }
        val distance = recovery.distanceMeters ?: return 0
        return TrimpDefaults.roundHalfUp(distance / METERS_PER_KM * jogPaceOf(ctx))
    }

    private fun jogPaceOf(ctx: IntervalContext): Int {
        ctx.paceBands.firstOrNull { it.zone == 1 && it.confidence in MEASURED }
            ?.centreSecPerKm?.let { return it }
        ctx.vdot?.let { vdot ->
            PaceZoneEngine.anchorSecPerKm(zone = 1, vdot = vdot)
                ?.let { return TrimpDefaults.roundHalfUp(it) }
        }
        return DEFAULT_JOG_SEC_PER_KM
    }

    // ---- what the UI prints ---------------------------------------------------------------------

    /**
     * The summary a card shows: "5 × 1000 m @ 3:54", "4 × 8:00 @ 271–299 W", "3 × 4:00 in Z5",
     * "2 × (10 × 0:30) @ 371 W". `null` when the structure has no work step at all.
     */
    fun summary(structure: WorkoutStructure): String? = IntervalStructures.summary(structure)

    /** `234` → `"3:54"`. */
    fun mmss(seconds: Int): String = IntervalStructures.mmss(seconds)

    // ---- the session's own target pace (§3.10.2) -----------------------------------------------

    /**
     * The pace `suggested_session.targetPaceSecPerKm` carries: the measured band of the session's
     * zone, else the Daniels anchor of that zone. `null` for a session type with no zone target —
     * and for every non-running session, where s/km says nothing.
     */
    fun targetPaceFor(sessionType: SessionType, ctx: IntervalContext): Int? {
        PaceZoneEngine.recommendedPaceFor(sessionType, ctx.paceBands)?.centreSecPerKm?.let { return it }
        val zone = recommendedZoneFor(sessionType) ?: return null
        val vdot = ctx.vdot ?: return null
        return PaceZoneEngine.anchorSecPerKm(zone, vdot)?.let { TrimpDefaults.roundHalfUp(it) }
    }

    /** §3.10.2: the first zone of the target range, except intervals (Z5) and tempo (Z4). */
    fun recommendedZoneFor(sessionType: SessionType): Int? {
        val target = SessionZoneTargets.targetFor(sessionType) ?: return null
        return when (sessionType) {
            SessionType.INTERVAL_RUN, SessionType.TEMPO_RUN -> target.last
            else -> target.first
        }
    }

    private const val SEC_PER_MIN: Int = 60
    private const val METERS_PER_KM: Double = 1_000.0
}
