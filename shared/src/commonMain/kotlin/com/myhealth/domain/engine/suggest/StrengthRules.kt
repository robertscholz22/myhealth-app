package com.myhealth.domain.engine.suggest

import com.myhealth.domain.engine.strength.MuscleLoadEngine
import com.myhealth.domain.engine.strength.MuscleLoadState
import com.myhealth.domain.engine.strength.StrengthTemplates
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.MuscleLoadBand
import com.myhealth.domain.model.RationaleEntry
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutKind

/**
 * The strength half of the suggester (PLAN §3.12.5, P14.5) — the rules that finally answer the
 * owner's "no leg day after an intense run, but upper body would be ok".
 *
 * | Rule | Effect |
 * |---|---|
 * | `C15` | a `STRENGTH_LOWER`/`STRENGTH_FULL` candidate is discarded when the day's projected lower-body band is `FATIGUED`, when a hard leg day sits within 36 h before it, or when a hard run / match / race sits within 48 h after it |
 * | bonus | `+0.10` for `STRENGTH_UPPER` on a loaded-legs / fresh-arms day, `+0.10` for leg work on fresh legs with nothing hard ahead |
 * | workout | a placed `STRENGTH_*` session proposes a built-in template, alternating per kind |
 * | mobility | a placed or filler `MOBILITY` session proposes a `MOBILITY_*` routine and says why (P17.1) |
 *
 * **The whole layer is inert when [MuscleContext.state] is `null`** — which is what every
 * pre-P14.5 fixture relies on: `Constraints` returns no `C15`, `Scorer` adds a `0.0` bonus,
 * `Rationale` appends no line and `SuggestionEngine` proposes no template, so `sug01`…`sug36`,
 * `ar01`…`ar08` and the whole `sug28` baseline stay byte-identical (`sug39`, `sug40`).
 *
 * Ambiguity notes (§3.12.5 leaves these open):
 * - "the day's **projected** lower-body band": [MuscleLoadState] is computed for today, so a later
 *   horizon day is evaluated by decaying today's load with the engine's own 48-hour half-life. A
 *   Sunday leg day is therefore allowed after a Saturday long run once the legs have recovered by
 *   Wednesday, without the caller having to recompute anything.
 * - "within 36 h **before**" is read in whole local days, the way C1/C11/C13 read their windows:
 *   the candidate's own day and the day before it ([HARD_LEG_LOOKBACK_DAYS]). "Within 48 h after"
 *   reuses `Constraints.HARD_WINDOW_DAYS` (the candidate day and the two days after it), so (c)
 *   really is C2 widened from matches and races to hard runs.
 * - a *hard leg day* is a completed activity of ≥ [HARD_LEG_TRIMP] AU in [LEG_SPORT_GROUPS] **or**
 *   a `HIGH`/`MAX` grid item in one of those groups, fixed or already suggested.
 * - the alternation is by "the most recently accepted template of that kind", extended inside one
 *   batch by the [occurrence] index, so a week holding two upper days gets `UPPER_A` then
 *   `UPPER_B` rather than the same workout twice.
 */
object StrengthRules {

    /** §P17: the three mobility routines `mobilityTemplateFor` chooses between. */
    const val MOBILITY_LOWER_A: String = "MOBILITY_LOWER_A"
    const val MOBILITY_UPPER_A: String = "MOBILITY_UPPER_A"
    const val MOBILITY_FULL_A: String = "MOBILITY_FULL_A"

    /** §3.12.5 (b): the sports whose hard sessions leave the legs unable to lift. */
    val LEG_SPORT_GROUPS: Set<SportGroup> = setOf(SportGroup.RUN, SportGroup.SOCCER, SportGroup.CYCLE)

    /** §3.12.5 (b): a completed activity at or above this TRIMP is a hard leg day. */
    const val HARD_LEG_TRIMP: Double = 150.0

    /** §3.12.5 (b): "within 36 h before" in whole days — yesterday and today. */
    const val HARD_LEG_LOOKBACK_DAYS: Long = 1L

    /** §3.12.5 (c): the running sessions a leg day may not sit in front of. */
    val HARD_RUN_TYPES: Set<SessionType> = setOf(
        SessionType.TEMPO_RUN,
        SessionType.INTERVAL_RUN,
        SessionType.LONG_RUN,
    )

    /** §3.12.5: the two candidates `C15` can discard. */
    val LEG_STRENGTH_TYPES: Set<SessionType> =
        setOf(SessionType.STRENGTH_LOWER, SessionType.STRENGTH_FULL)

    /** §3.12.5: the unweighted score bonus, folded in the way `cycleBonus` is. */
    const val SCORE_BONUS: Double = 0.10

    fun isLegStrength(sessionType: SessionType): Boolean = sessionType in LEG_STRENGTH_TYPES

    /** The workout kind a `STRENGTH_*` session asks for, or `null` for everything else. */
    fun kindFor(sessionType: SessionType): StrengthWorkoutKind? = when (sessionType) {
        SessionType.STRENGTH_UPPER -> StrengthWorkoutKind.UPPER
        SessionType.STRENGTH_LOWER -> StrengthWorkoutKind.LOWER
        SessionType.STRENGTH_FULL -> StrengthWorkoutKind.FULL
        else -> null
    }

    /** Today's lower-body load decayed forward to [day] and re-banded (see the class KDoc). */
    fun projectedLowerBand(day: Long, ctx: MuscleContext): MuscleLoadBand? =
        projectedBand(ctx.state?.lowerBodyLoad, day, ctx)

    /** Today's upper-body load decayed forward to [day] and re-banded. */
    fun projectedUpperBand(day: Long, ctx: MuscleContext): MuscleLoadBand? =
        projectedBand(ctx.state?.upperBodyLoad, day, ctx)

    private fun projectedBand(load: Double?, day: Long, ctx: MuscleContext): MuscleLoadBand? {
        val state = ctx.state ?: return null
        val ageDays = (day - ctx.todayDay).coerceAtLeast(0L).toDouble()
        return MuscleLoadEngine.bandFor((load ?: 0.0) * MuscleLoadEngine.decay(ageDays), state.ref)
    }

    /**
     * `C15` — the predicate `Constraints` calls. Inert without a [MuscleContext.state], and never
     * applied to anything but lower-body and full-body strength.
     */
    fun violatesC15(candidate: Candidate, day: Long, grid: SuggestionGrid, ctx: MuscleContext): Boolean {
        if (!ctx.enabled) return false
        if (!isLegStrength(candidate.sessionType)) return false
        return legWorkBlocked(day, grid, ctx)
    }

    /**
     * The three halves of `C15` without the session-type test: "would leg work be wrong on [day]?".
     * The rationale reads it too, which is how `C15_RESPECTED` can say *why* the upper-body day was
     * chosen over the leg day.
     */
    fun legWorkBlocked(day: Long, grid: SuggestionGrid, ctx: MuscleContext): Boolean {
        if (!ctx.enabled) return false
        if (projectedLowerBand(day, ctx) == MuscleLoadBand.FATIGUED) return true
        if (hardLegDayBefore(day, grid, ctx)) return true
        return hardEffortAfter(day, grid, ctx)
    }

    /** `C15` (b) — a hard leg day within 36 h before [day]. */
    fun hardLegDayBefore(day: Long, grid: SuggestionGrid, ctx: MuscleContext): Boolean {
        if (ctx.hardLegDays.any { day - it in 0..HARD_LEG_LOOKBACK_DAYS }) return true
        return grid.entries().any { (otherDay, item) ->
            otherDay != day &&
                day - otherDay in 0..HARD_LEG_LOOKBACK_DAYS &&
                item.isHard &&
                item.sportGroup in LEG_SPORT_GROUPS
        }
    }

    /** `C15` (c) — a hard run, a match or a race within 48 h after [day]; C2 widened. */
    fun hardEffortAfter(day: Long, grid: SuggestionGrid, ctx: MuscleContext): Boolean {
        if (ctx.keyEventDays.any { it - day in 0..Constraints.HARD_WINDOW_DAYS }) return true
        return grid.entries().any { (otherDay, item) ->
            otherDay != day &&
                otherDay - day in 0..Constraints.HARD_WINDOW_DAYS &&
                (item.sessionType in HARD_RUN_TYPES || item.isKeyEvent)
        }
    }

    /** §3.12.5's `Scorer.muscleBonus`, unweighted; `0.0` whenever the layer is off. */
    fun scoreBonus(candidate: Candidate, day: Long, grid: SuggestionGrid, ctx: MuscleContext): Double {
        if (!ctx.enabled) return 0.0
        val lower = projectedLowerBand(day, ctx)
        val upper = projectedUpperBand(day, ctx)
        val upperDayFits = candidate.sessionType == SessionType.STRENGTH_UPPER &&
            lower != MuscleLoadBand.FRESH &&
            upper == MuscleLoadBand.FRESH
        val legDayFits = isLegStrength(candidate.sessionType) &&
            lower == MuscleLoadBand.FRESH &&
            !hardEffortAfter(day, grid, ctx)
        return if (upperDayFits || legDayFits) SCORE_BONUS else 0.0
    }

    /**
     * The built-in template a placed `STRENGTH_*` session proposes, or `null` when the layer is off
     * or the session type has no workout kind. [occurrence] is the 0-based index of this session
     * among the same-kind sessions of the same batch, so a second upper day alternates further.
     */
    fun templateIdFor(sessionType: SessionType, ctx: MuscleContext, occurrence: Int = 0): String? {
        if (!ctx.enabled) return null
        if (sessionType == SessionType.MOBILITY) return mobilityTemplateFor(ctx.state)
        val kind = kindFor(sessionType) ?: return null
        val options = StrengthTemplates.ofKind(kind).mapNotNull { it.templateId }
        if (options.isEmpty()) return null
        // `indexOf` is −1 for "never accepted one", which makes the first option the next one.
        val start = options.indexOf(ctx.lastTemplateByKind[kind])
        return options[(start + 1 + occurrence.coerceAtLeast(0)) % options.size]
    }

    /**
     * P17.1 (§P17): the mobility routine the muscle-load state asks for.
     *
     * | State | Routine |
     * |---|---|
     * | lower ≥ `LOADED` and upper `FRESH` | [MOBILITY_LOWER_A] |
     * | upper ≥ `LOADED` and lower `FRESH` | [MOBILITY_UPPER_A] |
     * | anything else, `null` included | [MOBILITY_FULL_A] |
     *
     * A total function on purpose — there is always a routine to name, so the UI never has to
     * handle "mobility without a routine". *Whether* a session carries it is a separate decision
     * ([templateIdFor]), and that one is inert without a muscle-load state.
     */
    fun mobilityTemplateFor(state: MuscleLoadState?): String {
        val lower = state?.lowerBody ?: return MOBILITY_FULL_A
        val upper = state.upperBody
        val lowerLoaded = lower.ordinal >= MuscleLoadBand.LOADED.ordinal
        val upperLoaded = upper.ordinal >= MuscleLoadBand.LOADED.ordinal
        return when {
            lowerLoaded && upper == MuscleLoadBand.FRESH -> MOBILITY_LOWER_A
            upperLoaded && lower == MuscleLoadBand.FRESH -> MOBILITY_UPPER_A
            else -> MOBILITY_FULL_A
        }
    }

    /**
     * P17.1: the `MOBILITY_FOCUS` line of a placed or filler mobility session — which half of the
     * body the routine is aimed at, and why. `null` for anything that is not a `MOBILITY` session
     * and, like every other §3.12.5 line, whenever the muscle layer is off.
     */
    fun mobilityEntry(sessionType: SessionType, ctx: MuscleContext): RationaleEntry? {
        if (sessionType != SessionType.MOBILITY || !ctx.enabled) return null
        val text = when (mobilityTemplateFor(ctx.state)) {
            MOBILITY_LOWER_A ->
                "Mobility: legs are loaded, so this routine targets hips, hamstrings and calves."
            MOBILITY_UPPER_A ->
                "Mobility: the upper body is loaded, so this routine targets the chest, " +
                    "shoulders and upper back."
            else -> "Mobility: a full-body routine keeps everything moving."
        }
        return RationaleEntry(ruleId = Rationale.RULE_MOBILITY_FOCUS, text = text)
    }

    /**
     * P14.5 (§3.12.5): why *this* half of the body is being trained today. Emitted only for the
     * three `STRENGTH_*` types and only once the muscle layer is on — [RationaleContext] carries a
     * `null` band otherwise, so the list is empty and every earlier rationale is unchanged.
     */
    fun rationaleEntries(candidate: Candidate, ctx: RationaleContext): List<RationaleEntry> {
        val lower = ctx.muscleLowerBand ?: return emptyList()
        val entries = mutableListOf<RationaleEntry>()
        val isUpper = candidate.sessionType == SessionType.STRENGTH_UPPER
        if (isUpper && lower != MuscleLoadBand.FRESH) {
            entries += RationaleEntry(
                ruleId = Rationale.RULE_MUSCLE_LOWER_LOADED,
                text = "Legs are still ${bandLabel(lower)} from recent training — upper body works today.",
            )
        }
        if (isLegStrength(candidate.sessionType) && lower == MuscleLoadBand.FRESH) {
            entries += RationaleEntry(
                ruleId = Rationale.RULE_MUSCLE_LEGS_FRESH,
                text = "Legs are fresh and nothing hard is due in the next 48 h — a leg day fits here.",
            )
        }
        if (isUpper && ctx.muscleLegWorkBlocked) {
            entries += RationaleEntry(
                ruleId = Rationale.RULE_C15_RESPECTED,
                text = "Leg work would fall too close to hard running — kept off the legs.",
            )
        }
        return entries
    }

    /**
     * P14.5: the workout a placed `STRENGTH_*` session proposes ("Workout: Upper A — 6 exercises,
     * about 44 min"), attached where the interval lines are — once the session exists, not while
     * the candidate is being placed. `null` whenever no template was proposed.
     */
    fun workoutEntry(workout: StrengthWorkout?): RationaleEntry? {
        val row = workout ?: return null
        // P17: a mobility routine is announced by [mobilityEntry], not by the strength line.
        if (row.kind.isMobility) return null
        val count = row.exercises.size
        return RationaleEntry(
            ruleId = Rationale.RULE_STRENGTH_WORKOUT,
            text = "Workout: ${row.name} — $count ${if (count == 1) "exercise" else "exercises"}, " +
                "about ${row.estimatedMinutes} min.",
        )
    }

    private fun bandLabel(band: MuscleLoadBand): String = when (band) {
        MuscleLoadBand.FRESH -> "fresh"
        MuscleLoadBand.LOADED -> "loaded"
        MuscleLoadBand.FATIGUED -> "fatigued"
    }


    /** The template row itself — what the `STRENGTH_WORKOUT` rationale names. */
    fun templateFor(templateId: String?): StrengthWorkout? =
        templateId?.let { StrengthTemplates.byId(it) }

    // ---- P19: the checked workout pool --------------------------------------------------------

    /** The session types the pool decides about; `MOBILITY` keeps its rest-day role either way. */
    val POOL_GATED_TYPES: Set<SessionType> = setOf(
        SessionType.STRENGTH_FULL,
        SessionType.STRENGTH_UPPER,
        SessionType.STRENGTH_LOWER,
    )

    /**
     * §P19 item 9: the `STRENGTH_*` types none of the checked workouts maps to — the athlete
     * unchecked every one of them, so the suggester does not propose that kind of day at all.
     * Empty without a pool (`null`), which is the P14.5 behaviour.
     */
    fun excludedTypes(pool: List<StrengthWorkout>?): Set<SessionType> {
        if (pool == null) return emptySet()
        val covered = pool.map { it.kind.sessionType }.toSet()
        return POOL_GATED_TYPES - covered
    }

    /**
     * P19: a phase that prefers a strength type the pool cannot serve (e.g. `BUILD`'s
     * `STRENGTH_LOWER` with only upper-body workouts checked) passes the preference on to the
     * strength types the pool does cover — otherwise unchecking one kind would silently drop
     * strength from the whole week. Unchanged without a pool.
     */
    fun poolPreferred(preferred: Set<SessionType>, pool: List<StrengthWorkout>?): Set<SessionType> {
        val excluded = excludedTypes(pool)
        if (excluded.none { it in preferred }) return preferred
        return preferred - excluded + (POOL_GATED_TYPES - excluded)
    }

    /**
     * The workout a placed session proposes. Without a pool this is P14.5's built-in rotation
     * ([templateIdFor]); with one, the checked workouts whose kind maps to the session type,
     * ordered by id, the next one after the last accepted for that type. A `MOBILITY` session
     * keeps the muscle-state choice of kind and falls back to any checked mobility routine.
     * `null` whenever the muscle layer is off — the same gate as before.
     */
    fun choiceFor(sessionType: SessionType, ctx: MuscleContext, occurrence: Int = 0): WorkoutChoice? {
        if (!ctx.enabled) return null
        val pool = ctx.pool
            ?: return templateIdFor(sessionType, ctx, occurrence)
                ?.let { WorkoutChoice(templateId = it, workoutId = null, workout = templateFor(it)) }
        val options = poolOptions(sessionType, pool, ctx).sortedBy { it.id }
        if (options.isEmpty()) return null
        val last = ctx.lastWorkoutIdBySessionType[sessionType]
        val start = options.indexOfFirst { it.id == last }
        val chosen = options[(start + 1 + occurrence.coerceAtLeast(0)) % options.size]
        return WorkoutChoice(templateId = chosen.templateId, workoutId = chosen.id, workout = chosen)
    }

    private fun poolOptions(
        sessionType: SessionType,
        pool: List<StrengthWorkout>,
        ctx: MuscleContext,
    ): List<StrengthWorkout> {
        if (sessionType != SessionType.MOBILITY) {
            return pool.filter { !it.kind.isMobility && it.kind.sessionType == sessionType }
        }
        val wanted = when (mobilityTemplateFor(ctx.state)) {
            MOBILITY_LOWER_A -> StrengthWorkoutKind.MOBILITY_LOWER
            MOBILITY_UPPER_A -> StrengthWorkoutKind.MOBILITY_UPPER
            else -> StrengthWorkoutKind.MOBILITY_FULL
        }
        return pool.filter { it.kind == wanted }.ifEmpty { pool.filter { it.kind.isMobility } }
    }
}

/** P19: the workout a suggested session proposes — a built-in id, a concrete row, or both. */
data class WorkoutChoice(
    val templateId: String?,
    val workoutId: Long?,
    val workout: StrengthWorkout?,
)

/**
 * Whether this athlete's week knows anything about muscle load at all, and the facts every §3.12.5
 * rule hangs off — the strength counterpart of [BikeContext].
 *
 * [MuscleContext.NONE] (a `null` [state]) is the state every pre-P14.5 input is in, where every
 * rule above does nothing.
 */
data class MuscleContext(
    val todayDay: Long = 0L,
    val state: MuscleLoadState? = null,
    /** Days carrying a completed ≥ 150 AU run, match or ride (§3.12.5 b). */
    val hardLegDays: Set<Long> = emptySet(),
    /** Days carrying a match or a race, spanning the horizon **+ 3 days** (§3.12.5 c). */
    val keyEventDays: Set<Long> = emptySet(),
    /** The most recently accepted built-in per workout kind, for the alternation. */
    val lastTemplateByKind: Map<StrengthWorkoutKind, String> = emptyMap(),
    /** P19: the checked workouts; `null` = the built-in rotation of P14.5. */
    val pool: List<StrengthWorkout>? = null,
    /** P19: the most recently accepted workout per session type, for the pool's rotation. */
    val lastWorkoutIdBySessionType: Map<SessionType, Long> = emptyMap(),
) {
    val enabled: Boolean get() = state != null

    companion object {
        val NONE: MuscleContext = MuscleContext()

        fun of(input: SuggestionInput): MuscleContext {
            val state = input.muscleLoad ?: return NONE
            return MuscleContext(
                todayDay = input.todayDay,
                state = state,
                hardLegDays = input.recentActivities
                    .filter {
                        it.sportGroup in StrengthRules.LEG_SPORT_GROUPS &&
                            (it.trimp ?: 0.0) >= StrengthRules.HARD_LEG_TRIMP
                    }
                    .map { it.day }
                    .toSet(),
                keyEventDays = input.events
                    .filter { it.type == EventType.SOCCER_MATCH || it.type == EventType.RACE }
                    .map { it.occurrenceDay }
                    .toSet(),
                lastTemplateByKind = input.lastAcceptedTemplateByKind,
                pool = input.strengthPool,
                lastWorkoutIdBySessionType = input.lastWorkoutIdBySessionType,
            )
        }
    }
}
