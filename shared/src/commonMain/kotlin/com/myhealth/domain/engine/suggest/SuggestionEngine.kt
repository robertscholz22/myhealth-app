package com.myhealth.domain.engine.suggest

import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.RationaleEntry
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.SuggestionBatch
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.domain.model.WorkoutStructureCodec
import kotlin.time.Clock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One run of the suggester: the unsaved batch, its sessions, and the numbers behind them. */
data class SuggestionResult(
    /** Unsaved (`id = 0`): the repository (P6.5) assigns ids when it persists the batch. */
    val batch: SuggestionBatch,
    val sessions: List<SuggestedSession>,
    val phase: TrainingPhase,
    val weeklyTarget: Double,
    val inputsHash: String,
)

/**
 * The training-suggestion engine (PLAN §3.5.6): a deterministic, offline greedy planner.
 *
 * The nine steps of §3.5.6 map onto this class as: [SuggestionGrid.seed] (1), [fixedLoad]/
 * `remainingBudget` (2), [Periodization.compute] (3), [candidatesFor] + [Constraints.violations]
 * (4), [Scorer.score] + `candidateOrder` (5), the `while` loop in [generate] (6), [enforceRestDay]
 * / [downgradeBeforeKeyEvent] / [ActiveRecovery.apply] / [addMobilityToRestDays] (7a–d),
 * [Rationale.forSession] (8) and
 * [SuggestionInputsHash] (9).
 *
 * Determinism: no randomness, no clock reads except [SuggestionBatch.generatedAtMillis], every
 * ordering is total (score desc, day asc, sessionType ordinal asc), and the whole input is hashed.
 *
 * Ambiguity note on §3.5.4's `durationScale = clamp(remainingBudget / Σ(remaining planned
 * defaults), 0.7, 1.3)`: the plan does not say how many sessions are still "planned" at the moment
 * of a placement (the greedy loop does not know its own future). This implementation reads it as
 * "the budget still affordable in minutes of this session type, divided by the default minutes of
 * the sessions that would fill the remaining free days": the affordable minutes are
 * `remainingBudget / (0.30 * rpe)`, and the expected remaining count is
 * `min(freeRestDays, ceil(affordableMinutes / defaultMin))`. The ratio is 1.0 when the budget
 * matches the days available, shrinks (to 0.7) when the week is tight and stretches (to 1.3) when
 * few days are left — which is what keeps `sug16`'s "within 15 % of target" true.
 */
class SuggestionEngine(private val clock: Clock) {

    /** Step 5's total order; the two tie-breakers make the output byte-identical across runs. */
    private val candidateOrder: Comparator<Pair<Candidate, ScoreBreakdown>> =
        compareByDescending<Pair<Candidate, ScoreBreakdown>> { it.second.total }
            .thenBy { it.first.day }
            .thenBy { it.first.sessionType.ordinal }

    fun generate(input: SuggestionInput): SuggestionResult {
        val periodization = Periodization.compute(input)
        val ctx = ConstraintContext.of(input)
        val bike = BikeContext.of(input)
        val muscle = MuscleContext.of(input)
        val shape = WeekShape.of(input, periodization)
        var grid = SuggestionGrid.seed(input)
        var remaining = max(periodization.weeklyTarget - grid.fixedLoad, 0.0)
        val stopFloor = MIN_BUDGET_FRACTION * periodization.weeklyTarget

        val benchmark = placeBenchmark(input, periodization, grid, ctx, bike, muscle, remaining)
        benchmark?.let { (placed, load) ->
            grid = placed
            remaining = max(remaining - load, 0.0)
        }
        if (benchmark == null) {
            placeSideGoalSession(input, periodization, grid, ctx, bike, muscle, remaining, shape)
                ?.let { (placed, load) ->
                    grid = placed
                    remaining = max(remaining - load, 0.0)
                }
        }

        var iterations = 0
        while (iterations < MAX_ITERATIONS && remaining > 0.0 && remaining >= stopFloor) {
            iterations++
            val best = bestCandidate(input, periodization, grid, ctx, bike, remaining, shape) ?: break
            val (candidate, breakdown) = best
            if (breakdown.total < MIN_SCORE) break
            val rationale = Rationale.forSession(
                candidate = candidate,
                ctx = rationaleContext(
                    input, periodization, grid, ctx, bike, muscle, remaining, candidate,
                ),
            )
            grid = grid.place(candidate.day, candidate.asPlacedItem(breakdown.total, rationale))
            remaining = max(remaining - candidate.estTrimp, 0.0)
        }

        grid = enforceRestDay(grid)
        grid = downgradeBeforeKeyEvent(grid, ctx)
        grid = ActiveRecovery.apply(
            grid, ctx, bike, periodization.phase, input.cycleStatusByDay, periodization.isStarterWeek,
        )
        if (input.profile.mobilityOnRestDays) {
            grid = addMobilityToRestDays(grid, periodization.phase, periodization.isStarterWeek)
        }
        return resultOf(input, periodization, grid, muscle, shape)
    }

    /**
     * P19 (§P19 item 5): the benchmark pre-pass. When [GoalRules.benchmarkReason] says a time trial
     * is due, one `TIME_TRIAL` goes on the best-scoring day that passes every constraint, before
     * the greedy loop spends the budget — it is the one session the week is planned around. It
     * carries a nominal score of 1.0 so the rest-day post-pass never picks it as its victim.
     * Returns the new grid and the load it took, or `null` when nothing was placed.
     */
    @Suppress("LongParameterList")
    private fun placeBenchmark(
        input: SuggestionInput,
        periodization: PeriodizationResult,
        grid: SuggestionGrid,
        ctx: ConstraintContext,
        bike: BikeContext,
        muscle: MuscleContext,
        remaining: Double,
    ): Pair<SuggestionGrid, Double>? {
        val reason = GoalRules.benchmarkReason(input, periodization.phase, periodization.isStarterWeek)
            ?: return null
        val entry = SessionCatalog.entryFor(SessionType.TIME_TRIAL) ?: return null
        return placeKeySession(
            entry, GoalRules.benchmarkEntry(reason), input, periodization, grid, ctx, bike, muscle, remaining,
        )
    }

    /**
     * 0.8.1: the side-goal pre-pass. While [GoalRules.sideGoal] names a short goal next to the long
     * one the week is built for, one `INTERVAL_RUN` (built as 1000 m reps for that goal, see
     * [IntervalBuilder]) goes on the best day the constraints allow — unless the week already
     * holds a planned interval run or time trial, or the benchmark pre-pass placed one. The greedy
     * loop gets no further `INTERVAL_RUN` ([WeekShape.excludedTypes]), so it takes a run slot an
     * easy run would otherwise have had.
     */
    @Suppress("LongParameterList")
    private fun placeSideGoalSession(
        input: SuggestionInput,
        periodization: PeriodizationResult,
        grid: SuggestionGrid,
        ctx: ConstraintContext,
        bike: BikeContext,
        muscle: MuscleContext,
        remaining: Double,
        shape: WeekShape,
    ): Pair<SuggestionGrid, Double>? {
        val goal = shape.intervalCtx?.sideGoal ?: return null
        val alreadyThere = grid.days.any { plan ->
            plan.items.any { it.sessionType in SIDE_GOAL_COVERED_BY }
        }
        if (alreadyThere) return null
        val entry = SessionCatalog.entryFor(SessionType.INTERVAL_RUN) ?: return null
        return placeKeySession(
            entry, GoalRules.sideGoalEntry(goal), input, periodization, grid, ctx, bike, muscle, remaining,
        )
    }

    /** A pre-placed session on its best legal day, with a nominal top score; `null` if no day fits. */
    @Suppress("LongParameterList")
    private fun placeKeySession(
        entry: CatalogEntry,
        reason: RationaleEntry,
        input: SuggestionInput,
        periodization: PeriodizationResult,
        grid: SuggestionGrid,
        ctx: ConstraintContext,
        bike: BikeContext,
        muscle: MuscleContext,
        remaining: Double,
    ): Pair<SuggestionGrid, Double>? {
        val scoring = Scorer.contextOf(input, periodization, remaining)
        val (candidate, _) = grid.days
            .map { Candidate(entry, it.day, entry.defaultMin) }
            .filter { Constraints.violations(it, it.day, grid, ctx).isEmpty() }
            .map { it to Scorer.score(it, grid, scoring) }
            .minWithOrNull(candidateOrder)
            ?: return null
        val rationale = Rationale.forSession(
            candidate = candidate,
            ctx = rationaleContext(input, periodization, grid, ctx, bike, muscle, remaining, candidate),
        ) + reason
        val placed = grid.place(candidate.day, candidate.asPlacedItem(BENCHMARK_SCORE, rationale))
        return placed to candidate.estTrimp
    }

    // ---- steps 4 + 5 -----------------------------------------------------------------------------

    private fun bestCandidate(
        input: SuggestionInput,
        periodization: PeriodizationResult,
        grid: SuggestionGrid,
        ctx: ConstraintContext,
        bike: BikeContext,
        remaining: Double,
        shape: WeekShape = WeekShape.NONE,
    ): Pair<Candidate, ScoreBreakdown>? {
        val scoring = Scorer.contextOf(input, periodization, remaining)
        return grid.days
            .flatMap { plan -> candidatesFor(plan.day, periodization.phase, grid, remaining, bike, shape) }
            .filter { Constraints.violations(it, it.day, grid, ctx).isEmpty() }
            .map { it to Scorer.score(it, grid, scoring) }
            .minWithOrNull(candidateOrder)
    }

    /**
     * Every catalog session the day could take, at the duration the budget and phase imply.
     *
     * [bike] is where P12.3 enters: it drops the four cycling rows for an athlete who does not
     * ride, drops `TRAINER_SESSION` without a trainer, and moves outdoor rides onto the trainer in
     * the indoor season — all before a single constraint or score is evaluated.
     */
    internal fun candidatesFor(
        day: Long,
        phase: TrainingPhase,
        grid: SuggestionGrid,
        remaining: Double,
        bike: BikeContext = BikeContext.NONE,
        shape: WeekShape = WeekShape.NONE,
    ): List<Candidate> = SessionCatalog.suggestableFor(bike.enabled)
        .filter { it.sessionType !in shape.excludedTypes }
        .mapNotNull { entry -> BikeRules.entryFor(entry, day, bike) }
        .map { entry ->
            // P19: a race build-up fixes the long run's length; everything else scales with the budget.
            val fixed = shape.longRunMinutes.takeIf { entry.sessionType == SessionType.LONG_RUN }
            Candidate(entry = entry, day = day, minutes = fixed ?: minutesFor(entry, phase, grid, remaining))
        }

    /** §3.5.4's duration scaling — see the class KDoc for how `Σ(remaining defaults)` is read. */
    fun minutesFor(
        entry: CatalogEntry,
        phase: TrainingPhase,
        grid: SuggestionGrid,
        remaining: Double,
    ): Int {
        val baseMin = max(
            MIN_SESSION_MINUTES,
            TrimpDefaults.roundHalfUp(entry.defaultMin * Scorer.durationFactor(phase, entry.sessionType)),
        )
        val perMinute = TrimpDefaults.RPE_TO_TRIMP * entry.rpe
        if (perMinute <= 0.0) return baseMin
        val affordableMinutes = remaining / perMinute
        val freeDays = max(1, grid.days.count { it.isRestDay && !it.isBlocked })
        val expected = min(freeDays, max(1, ceil(affordableMinutes / baseMin).toInt()))
        val scale = SessionCatalog.durationScale(affordableMinutes, (expected * baseMin).toDouble())
        return SessionCatalog.scaledMinutes(baseMin, scale)
    }

    // ---- step 7: post-passes ---------------------------------------------------------------------

    /** 7a — drop the lowest-scoring placement in any window that lost its rest day. */
    internal fun enforceRestDay(grid: SuggestionGrid): SuggestionGrid {
        var current = grid
        repeat(MAX_ITERATIONS) {
            val offending = current.rollingWindows()
                .firstOrNull { window -> current.days.none { it.day in window && it.isRestDay } }
                ?: return current
            val victim = current.suggested()
                .filter { it.first in offending && !it.second.isRestDayFiller }
                .minByOrNull { it.second.score }
                ?: return current
            current = current.remove(victim.first, victim.second)
        }
        return current
    }

    /** 7b — anything above `LOW` on the eve of a match or race becomes an easy run, or goes. */
    internal fun downgradeBeforeKeyEvent(grid: SuggestionGrid, ctx: ConstraintContext): SuggestionGrid {
        var current = grid
        val easy = SessionCatalog.entryFor(SessionType.EASY_RUN) ?: return current
        grid.days.forEach { plan ->
            val next = plan.day + 1
            val keyTomorrow = next in ctx.matchOrRaceDays || grid.itemsOn(next).any { it.isKeyEvent }
            if (!keyTomorrow) return@forEach
            val label = if (next in ctx.matchOrRaceDays) "a match or race" else "a match"
            plan.items
                .filter { it.origin == ItemOrigin.SUGGESTED && it.intensity.ordinal > Intensity.LOW.ordinal }
                .forEach { item ->
                    current = current.remove(plan.day, item)
                    val replacement = Candidate(easy, plan.day)
                    if (Constraints.violations(replacement, plan.day, current, ctx).isEmpty()) {
                        current = current.place(
                            plan.day,
                            replacement.asPlacedItem(
                                score = item.score,
                                rationale = item.rationale + Rationale.downgradeEntry(label),
                            ),
                        )
                    }
                }
        }
        return current
    }

    /**
     * 7d — `profile.mobilityOnRestDays`: every rest day gets mobility; a rest day stays a rest day.
     * The routine it names is chosen later, in [sessionOf], from the muscle-load state (P17.1).
     */
    internal fun addMobilityToRestDays(
        grid: SuggestionGrid,
        phase: TrainingPhase,
        isStarterWeek: Boolean = false,
    ): SuggestionGrid {
        val entry = SessionCatalog.entryFor(SessionType.MOBILITY) ?: return grid
        var current = grid
        grid.days.forEach { plan ->
            val alreadyThere = plan.items.any { it.isMobility }
            if (!plan.isRestDay || plan.isBlocked || alreadyThere) return@forEach
            current = current.place(
                plan.day,
                Candidate(entry, plan.day).asPlacedItem(
                    score = MOBILITY_SCORE,
                    rationale = Rationale.forMobility(phase, isStarterWeek),
                ),
            )
        }
        return current
    }

    // ---- steps 8 + 9 ------------------------------------------------------------------------------

    private fun rationaleContext(
        input: SuggestionInput,
        periodization: PeriodizationResult,
        grid: SuggestionGrid,
        ctx: ConstraintContext,
        bike: BikeContext,
        muscle: MuscleContext,
        remaining: Double,
        candidate: Candidate,
    ): RationaleContext {
        val nextKeyEvent = ctx.matchOrRaceDays
            .filter { it > candidate.day && it - candidate.day <= Constraints.HARD_WINDOW_DAYS }
            .minOrNull()
        val cap = ctx.weeklyCaps[candidate.sportGroup]
        return RationaleContext(
            phase = periodization.phase,
            weeklyTarget = periodization.weeklyTarget,
            remainingBudget = remaining,
            recoveryScore = input.recovery?.score,
            recoveryBand = periodization.band,
            hoursToKeyEvent = nextKeyEvent?.let { (it - candidate.day) * HOURS_PER_DAY },
            primaryGoalTitle = input.goals.minByOrNull { it.priority }?.title,
            sportCap = cap,
            sportUsed = if (cap == null) 0 else {
                Scorer.sessionsThisWeekForSport(candidate.sportGroup, candidate.day, grid)
            },
            cycleStatus = input.cycleStatusByDay[candidate.day],
            bikeGoal = BikeRules.primaryBikeGoal(input.goals),
            bikeIndoorSeason = bike.trainerAvailable && BikeRules.isIndoorSeason(candidate.day),
            isStarterWeek = periodization.isStarterWeek,
            downWeekReason = periodization.downWeekReason,
            muscleLowerBand = StrengthRules.projectedLowerBand(candidate.day, muscle),
            muscleUpperBand = StrengthRules.projectedUpperBand(candidate.day, muscle),
            muscleLegWorkBlocked = StrengthRules.legWorkBlocked(candidate.day, grid, muscle),
        )
    }

    private fun resultOf(
        input: SuggestionInput,
        periodization: PeriodizationResult,
        grid: SuggestionGrid,
        muscle: MuscleContext,
        shape: WeekShape = WeekShape.NONE,
    ): SuggestionResult {
        val intervalCtx = shape.intervalCtx ?: IntervalContext.of(input, periodization)
        val seen = mutableMapOf<SessionType, Int>()
        val stridesDay = stridesDayOf(grid, intervalCtx)
        val sessions = grid.suggested()
            .sortedWith(compareBy({ it.first }, { it.second.sessionType.ordinal }))
            .map { (day, item) ->
                val occurrence = seen.getOrElse(item.sessionType) { 0 }
                seen[item.sessionType] = occurrence + 1
                val session = sessionOf(day, item, intervalCtx, muscle, occurrence, shape.longRun)
                val strides = intervalCtx.sideGoal
                    ?.takeIf { day == stridesDay && item.sessionType in STRIDES_CARRIERS }
                if (strides == null) {
                    session
                } else {
                    session.copy(rationale = session.rationale + GoalRules.sideGoalStridesEntry(strides))
                }
            }
        val hash = SuggestionInputsHash.of(input)
        return SuggestionResult(
            batch = SuggestionBatch(
                id = 0L,
                generatedAtMillis = clock.now().toEpochMilliseconds(),
                horizonStartDay = input.todayDay,
                horizonEndDay = input.horizonEndDay,
                phase = periodization.phase,
                weeklyLoadTarget = periodization.weeklyTarget,
                inputsHash = hash,
                status = SuggestionStatus.PROPOSED,
            ),
            sessions = sessions,
            phase = periodization.phase,
            weeklyTarget = periodization.weeklyTarget,
            inputsHash = hash,
        )
    }

    /**
     * 0.8.1: the day of the week's first suggested easy run (else long run, else recovery run), when a side goal exists but the week
     * ended up with no interval run or time trial at all (the pre-pass found no legal day); `null`
     * otherwise. That run carries the side goal's strides line.
     */
    private fun stridesDayOf(grid: SuggestionGrid, ctx: IntervalContext): Long? {
        if (ctx.sideGoal == null) return null
        val covered = grid.days.any { plan -> plan.items.any { it.sessionType in SIDE_GOAL_COVERED_BY } }
        if (covered) return null
        val suggested = grid.suggested()
        return STRIDES_CARRIERS.firstNotNullOfOrNull { type ->
            suggested.filter { it.second.sessionType == type }.minOfOrNull { it.first }
        }
    }

    /**
     * P14.3 (§3.11): the placed item as a [SuggestedSession], with the structured workout and the
     * target pace the zone model prescribes for it — and, since P14.5, the built-in strength
     * workout a `STRENGTH_*` session proposes (§3.12.5), alternating by [occurrence] inside the
     * batch and by the last accepted template across batches. Since P17.1 a `MOBILITY` session —
     * the rest-day filler of [addMobilityToRestDays] or a placed one — names a `MOBILITY_*` routine
     * the same way, with a `MOBILITY_FOCUS` line instead of the strength `Workout:` one.
     *
     * Without muscle load there is no template, no `STRENGTH_WORKOUT` line, no `MOBILITY_FOCUS`
     * line and no change at all: every pre-P17 output, `sug28`'s baseline included, is untouched.
     * (§P17 asks for the mobility id "always"; `sug39` pins that a muscle-load-free week names no
     * template at all, so the id follows the same gate as every other §3.12.5 output — see the
     * P17.1 note in PLAN §P17.)
     *
     * Nothing here can change *which* sessions were placed — candidate generation, scoring and the
     * constraints all ran already. Without a VDOT, a measured band or an FTP the structure is
     * zone-only and the pace is `null`, and [Rationale.intervalEntries] then adds no line at all.
     */
    private fun sessionOf(
        day: Long,
        item: GridItem,
        ctx: IntervalContext,
        muscle: MuscleContext = MuscleContext.NONE,
        occurrence: Int = 0,
        longRun: LongRunPlan? = null,
    ): SuggestedSession {
        val entry = SessionCatalog.entryFor(item.sessionType)
        val plan = entry?.let { IntervalBuilder.plan(Candidate(it, day, item.minutes), ctx) }
        val isTimeTrial = item.sessionType == SessionType.TIME_TRIAL
        val pace = when {
            isTimeTrial -> GoalRules.benchmarkPaceSecPerKm(ctx.vdot)
            item.sportGroup == SportGroup.RUN -> IntervalBuilder.targetPaceFor(item.sessionType, ctx)
            else -> null
        }
        val choice = StrengthRules.choiceFor(item.sessionType, muscle, occurrence)
        val workout = choice?.workout
        val longRunHere = longRun?.takeIf { item.sessionType == SessionType.LONG_RUN }
        val distance = when {
            isTimeTrial -> GoalRules.BENCHMARK_DISTANCE_M
            longRunHere != null -> longRunHere.meters
            else -> null
        }
        return SuggestedSession(
            id = 0L,
            batchId = 0L,
            day = day,
            sportType = item.sportType,
            sessionType = item.sessionType,
            intensity = item.intensity,
            targetDurationMin = item.minutes,
            targetDistanceMeters = distance,
            estimatedTrimp = item.estTrimp,
            score = item.score,
            rationale = item.rationale +
                Rationale.intervalEntries(plan, pace, ctx, item.sessionType) +
                listOfNotNull(
                    longRunHere?.let { GoalRules.longRunEntry(it) },
                    StrengthRules.workoutEntry(workout),
                    StrengthRules.mobilityEntry(item.sessionType, muscle),
                ),
            status = SuggestionStatus.PROPOSED,
            targetPaceSecPerKm = pace,
            structureJson = plan?.let { WorkoutStructureCodec.encode(it.structure) },
            workoutTemplateId = choice?.templateId,
            workoutId = choice?.workoutId,
        )
    }

    companion object {
        /** §3.5.6 step 6: a candidate below this score is not worth placing. */
        const val MIN_SCORE: Double = 0.35

        /** …and the loop stops once less than 10 % of the weekly budget is left. */
        const val MIN_BUDGET_FRACTION: Double = 0.10

        const val MAX_ITERATIONS: Int = 20

        /** P19: the benchmark's nominal score — highest, so no post-pass removes it first. */
        const val BENCHMARK_SCORE: Double = 1.0

        /** Post-pass 7c/7d fillers are not scored candidates; they carry this nominal score. */
        const val MOBILITY_SCORE: Double = 0.0

        const val MIN_SESSION_MINUTES: Int = 10

        /** A week that already holds one of these needs no side-goal session of its own. */
        /** The runs that may carry the strides fallback, in order of preference. */
        private val STRIDES_CARRIERS: List<SessionType> =
            listOf(SessionType.EASY_RUN, SessionType.LONG_RUN, SessionType.RECOVERY_RUN)

        private val SIDE_GOAL_COVERED_BY: Set<SessionType> =
            setOf(SessionType.INTERVAL_RUN, SessionType.TIME_TRIAL)

        private const val HOURS_PER_DAY: Long = 24L
    }
}

/**
 * P19: the per-run facts that shape *which* candidates exist and how long the long run is —
 * computed once per [SuggestionEngine.generate]. [NONE] is the pre-P19 shape (nothing excluded,
 * no fixed long run), which is what every caller without a goal layer or a pool gets.
 */
internal data class WeekShape(
    val excludedTypes: Set<SessionType> = emptySet(),
    val longRun: LongRunPlan? = null,
    val longRunMinutes: Int? = null,
    val intervalCtx: IntervalContext? = null,
) {
    companion object {
        val NONE: WeekShape = WeekShape()

        fun of(input: SuggestionInput, periodization: PeriodizationResult): WeekShape {
            val intervalCtx = IntervalContext.of(input, periodization)
            val longRun = GoalRules.longRunPlan(input, periodization.phase)
            // 0.8.1: the side-goal pre-pass owns the week's interval run; the loop adds no other.
            val sideGoalTypes = if (intervalCtx.sideGoal != null) setOf(SessionType.INTERVAL_RUN) else emptySet()
            return WeekShape(
                excludedTypes = StrengthRules.excludedTypes(input.strengthPool) + sideGoalTypes,
                longRun = longRun,
                longRunMinutes = longRun?.let {
                    GoalRules.longRunMinutes(it, IntervalBuilder.targetPaceFor(SessionType.LONG_RUN, intervalCtx))
                },
                intervalCtx = intervalCtx,
            )
        }
    }
}
