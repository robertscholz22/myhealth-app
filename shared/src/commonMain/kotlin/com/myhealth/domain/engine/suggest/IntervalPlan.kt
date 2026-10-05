package com.myhealth.domain.engine.suggest

import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.engine.running.PaceZoneBand
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.domain.model.WorkoutStep
import com.myhealth.domain.model.WorkoutStepKind
import com.myhealth.domain.model.WorkoutStructure
import com.myhealth.domain.model.WorkoutTargetKind
import com.myhealth.domain.util.pad2

/**
 * Everything [IntervalBuilder] needs that is not in the candidate itself (PLAN §3.11).
 *
 * All of it is optional: without a [vdot], [paceBands] and [ftpWatts] the builder still produces a
 * structure — a zone-only one (selection rule 6) — which is what keeps the pre-P14 output of the
 * suggester unchanged (the rationale lines are only emitted once there is a pace or a power to
 * name; see [Rationale.intervalEntries]).
 */
data class IntervalContext(
    val phase: TrainingPhase,
    /** The primary goal's distance — §3.11 rule 3 picks a template off it. */
    val goalDistanceMeters: Double? = null,
    val vdot: Double? = null,
    val paceBands: List<PaceZoneBand> = emptyList(),
    val ftpWatts: Int? = null,
    val acwr: Double? = null,
    val recoveryBand: RecoveryBand? = null,
    val isStarterWeek: Boolean = false,
    /** P19: the race-time goal the reps are measured against; `null` while the goal layer is off. */
    val paceGoal: PaceGoal? = null,
    /** 0.8.1: a short goal next to a longer one — its `INTERVAL_RUN` is built as 1000 m reps for it. */
    val sideGoal: PaceGoal? = null,
) {
    companion object {
        fun of(input: SuggestionInput, periodization: PeriodizationResult): IntervalContext =
            IntervalContext(
                phase = periodization.phase,
                goalDistanceMeters = goalDistanceOf(input),
                vdot = input.vdot,
                paceBands = input.paceBands,
                ftpWatts = input.ftpWatts,
                acwr = periodization.acwr,
                recoveryBand = periodization.band,
                isStarterWeek = periodization.isStarterWeek,
                paceGoal = GoalRules.paceGoal(input),
                sideGoal = GoalRules.sideGoal(input, periodization.phase, periodization.isStarterWeek),
            )

        /** The race the week is periodized towards, else the highest-priority goal with a distance. */
        private fun goalDistanceOf(input: SuggestionInput): Double? =
            Periodization.primaryRaceGoal(input.goals, input.todayDay)?.targetDistanceMeters
                ?: input.goals
                    .filter { it.targetDistanceMeters != null }
                    .minByOrNull { it.priority }
                    ?.targetDistanceMeters
    }
}

/**
 * A built session: the structure plus the numbers that went into it, so the rationale can say
 * *why* it looks like this without re-deriving anything.
 */
data class IntervalPlan(
    val template: IntervalTemplate,
    val reps: Int,
    val workSec: Int,
    val structure: WorkoutStructure,
    val workPaceSecPerKm: Int?,
    val workPowerLowW: Int?,
    val workPowerHighW: Int?,
    val recoverySec: Int,
    /** True when §3.11 rule 2's `TAPER`/`RACE_WEEK` shortening actually removed work. */
    val shortenedForTaper: Boolean,
    val summary: String,
    /** P19: the work pace is the goal pace (§P19 item 6), not the current Daniels/measured pace. */
    val usesGoalPace: Boolean = false,
) {
    /** A structure worth explaining: it prescribes more than "be in this zone". */
    val hasQuantifiedTarget: Boolean get() = workPaceSecPerKm != null || workPowerLowW != null
}


/**
 * The value types of [IntervalBuilder] and the step assembly that turns an [IntervalTemplate] plus
 * the athlete's numbers into a [WorkoutStructure] (PLAN §3.11) — split out of `IntervalBuilder.kt`
 * for R10. The *decisions* (which template, how many reps, which target) all live in the builder;
 * nothing here chooses anything.
 */
object IntervalStructures {

    @Suppress("LongParameterList")
    fun structureOf(
        template: IntervalTemplate,
        reps: Int,
        workSec: Int,
        workPace: Int?,
        power: Pair<Int, Int>?,
        recoverySec: Int,
        recoveryPower: Pair<Int, Int>? = null,
    ): WorkoutStructure {
        val work = stepOf(WorkoutStepKind.WORK, template, template.work, workSec, workPace, power)
        val body = if (template.isContinuous) {
            listOf(work)
        } else {
            val recovery = stepOf(
                kind = WorkoutStepKind.RECOVERY,
                template = template,
                spec = template.recovery ?: template.work,
                durationSec = recoverySec,
                pace = null,
                power = recoveryPower,
            )
            listOf(
                WorkoutStep(
                    kind = WorkoutStepKind.REPEAT,
                    repeat = reps,
                    children = listOf(work, recovery),
                ),
            )
        }
        return WorkoutStructure(
            templateId = template.id,
            steps = listOf(warmup(template)) + body + cooldown(template),
        )
    }

    @Suppress("LongParameterList")
    fun stepOf(
        kind: WorkoutStepKind,
        template: IntervalTemplate,
        spec: IntervalStepSpec,
        durationSec: Int,
        pace: Int?,
        power: Pair<Int, Int>?,
    ): WorkoutStep = WorkoutStep(
        kind = kind,
        repeat = template.innerReps,
        durationSec = if (spec.isDistanceBased) null else durationSec,
        distanceMeters = spec.distanceMeters,
        target = when {
            power != null -> WorkoutTargetKind.POWER
            pace != null -> WorkoutTargetKind.PACE
            template.isEffort && kind == WorkoutStepKind.WORK -> WorkoutTargetKind.EFFORT
            else -> WorkoutTargetKind.ZONE
        },
        zone = spec.zone,
        paceLowSecPerKm = pace?.let { TrimpDefaults.roundHalfUp(it * (1.0 - IntervalCatalog.PACE_TOLERANCE)) },
        paceHighSecPerKm = pace?.let { TrimpDefaults.roundHalfUp(it * (1.0 + IntervalCatalog.PACE_TOLERANCE)) },
        powerLowW = power?.first,
        powerHighW = power?.second,
        note = spec.note,
    )

    /** `BIKE_30_30`'s recovery is prescribed at 50 % FTP; every other recovery is just easy. */
    fun recoveryPowerOf(template: IntervalTemplate, ctx: IntervalContext): Pair<Int, Int>? {
        val ftp = ctx.ftpWatts ?: return null
        val recovery = template.recovery ?: return null
        val low = recovery.ftpLowPct ?: return null
        val high = recovery.ftpHighPct ?: low
        return TrimpDefaults.roundHalfUp(ftp * low) to TrimpDefaults.roundHalfUp(ftp * high)
    }

    fun warmup(template: IntervalTemplate): WorkoutStep = WorkoutStep(
        kind = WorkoutStepKind.WARMUP,
        durationSec = template.warmupSec,
        target = WorkoutTargetKind.ZONE,
        zone = 1,
        note = "Easy, Z1–Z2",
    )

    fun cooldown(template: IntervalTemplate): List<WorkoutStep> = listOf(
        WorkoutStep(
            kind = WorkoutStepKind.COOLDOWN,
            durationSec = template.cooldownSec,
            target = WorkoutTargetKind.ZONE,
            zone = 1,
        ),
    )

    // ---- the one-liner ------------------------------------------------------------------------

    /**
     * The summary a card shows: "5 × 1000 m @ 3:54", "4 × 8:00 @ 271–299 W", "3 × 4:00 in Z5",
     * "2 × (10 × 0:30) @ 371 W". `null` when the structure has no work step at all.
     */
    fun summary(structure: WorkoutStructure): String? {
        val repeat = structure.steps.firstOrNull { it.kind == WorkoutStepKind.REPEAT }
        val work = repeat?.children?.firstOrNull { it.kind == WorkoutStepKind.WORK }
            ?: structure.steps.firstOrNull { it.kind == WorkoutStepKind.WORK }
            ?: return null
        val outer = repeat?.repeat ?: 1
        val inner = work.repeat
        val unit = work.distanceMeters?.let { "${TrimpDefaults.roundHalfUp(it)} m" }
            ?: mmss(work.durationSec ?: 0)
        val core = when {
            inner > 1 -> "$outer × ($inner × $unit)"
            outer > 1 -> "$outer × $unit"
            else -> unit
        }
        return core + targetSuffix(work)
    }

    fun targetSuffix(work: WorkoutStep): String {
        val low = work.powerLowW
        val high = work.powerHighW
        if (low != null && high != null) {
            return if (low == high) " @ $low W" else " @ $low–$high W"
        }
        val paceLow = work.paceLowSecPerKm
        val paceHigh = work.paceHighSecPerKm
        if (paceLow != null && paceHigh != null) return " @ ${mmss((paceLow + paceHigh) / 2)}"
        return work.zone?.let { " in Z$it" } ?: ""
    }

    /** `234` → `"3:54"`. */
    fun mmss(seconds: Int): String = "${seconds / SEC_PER_MIN}:${(seconds % SEC_PER_MIN).pad2()}"

    private const val SEC_PER_MIN: Int = 60
}
