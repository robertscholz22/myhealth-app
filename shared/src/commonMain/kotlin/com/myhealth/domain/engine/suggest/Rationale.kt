package com.myhealth.domain.engine.suggest

import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.model.CycleConfidence
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.MuscleLoadBand
import com.myhealth.domain.model.RationaleEntry
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.domain.util.NumberFormat

/** What [Rationale.forSession] needs to explain one placement (PLAN §3.5.6 step 8). */
data class RationaleContext(
    val phase: TrainingPhase,
    val weeklyTarget: Double,
    /** Budget still unallocated **before** this session is subtracted. */
    val remainingBudget: Double,
    val recoveryScore: Int? = null,
    val recoveryBand: RecoveryBand? = null,
    /** Hours from the candidate's day to the next match/race, when one is close (C1/C2). */
    val hoursToKeyEvent: Long? = null,
    val keyEventLabel: String = "match",
    val primaryGoalTitle: String? = null,
    val sportCap: Int? = null,
    val sportUsed: Int = 0,
    /** P11.2: where the candidate's day sits in the cycle; `null` when tracking is off. */
    val cycleStatus: CycleStatus? = null,
    /** P12.3: the highest-priority active `BIKE_*` goal, when there is one. */
    val bikeGoal: Goal? = null,
    /** P12.3: true when this day's rides were moved onto the trainer ([BikeRules.movesIndoors]). */
    val bikeIndoorSeason: Boolean = false,
    /** POLISH-10: true when [Periodization.isStarterWeek] fired for this batch. */
    val isStarterWeek: Boolean = false,
    /** P19.6: why this week is a down week; `null` outside `RECOVERY_WEEK`. */
    val downWeekReason: DownWeekReason? = null,
    /**
     * P14.5: the candidate day's **projected** lower/upper band (§3.12.4's decay applied forward),
     * or `null` when `SuggestionInput.muscleLoad` was not supplied — in which case none of the three
     * muscle lines is ever emitted and every pre-P14.5 rationale is byte-identical.
     */
    val muscleLowerBand: MuscleLoadBand? = null,
    val muscleUpperBand: MuscleLoadBand? = null,
    /** P14.5: true when `C15` would have discarded leg work on this day ([StrengthRules]). */
    val muscleLegWorkBlocked: Boolean = false,
)

/**
 * The `{ruleId, text}` rationale of PLAN §3.5.6 step 8 — the "why" every suggestion card shows.
 *
 * Rule ids are stable identifiers (`PHASE_BUILD`, `BUDGET`, `C1_RESPECTED`, `RECOVERY_GOOD`, …) so
 * the UI can group or icon them later; the text is a full English sentence. The phase entry is
 * always emitted, which is what makes the §3.5.7 `sug15` guarantee ("every session has a non-empty
 * rationale") hold by construction.
 */
object Rationale {

    const val RULE_BUDGET: String = "BUDGET"
    const val RULE_C1_RESPECTED: String = "C1_RESPECTED"
    const val RULE_C4_RESPECTED: String = "C4_RESPECTED"
    const val RULE_SPORT_CAP: String = "SPORT_CAP"
    const val RULE_MOBILITY_REST_DAY: String = "MOBILITY_REST_DAY"
    const val RULE_ACTIVE_RECOVERY: String = "ACTIVE_RECOVERY"
    const val RULE_DOWNGRADED: String = "DOWNGRADED_BEFORE_EVENT"
    const val RULE_STARTER_WEEK: String = "STARTER_WEEK"
    const val RULE_DOWN_WEEK: String = "DOWN_WEEK"

    /** P14.3's three interval ids (§3.11); only ever attached to a structured session. */
    const val RULE_INTERVAL_STRUCTURE: String = "INTERVAL_STRUCTURE"
    const val RULE_INTERVAL_SHORTENED_TAPER: String = "INTERVAL_SHORTENED_TAPER"
    const val RULE_PACE_TARGET: String = "PACE_TARGET"

    /** P19: the run sessions that carry a `GOAL_PACE` line. */
    private val GOAL_PACE_SESSION_TYPES: Set<SessionType> =
        setOf(SessionType.TEMPO_RUN, SessionType.INTERVAL_RUN)

    /** P14.5's four strength ids (§3.12.5); only ever attached while muscle load is known. */
    const val RULE_MUSCLE_LOWER_LOADED: String = "MUSCLE_LOWER_LOADED"
    const val RULE_MUSCLE_LEGS_FRESH: String = "MUSCLE_LEGS_FRESH"
    const val RULE_C15_RESPECTED: String = "C15_RESPECTED"
    const val RULE_STRENGTH_WORKOUT: String = "STRENGTH_WORKOUT"

    /**
     * P17.1's mobility id (§P17): why *this* mobility routine. Like the four above it is only ever
     * attached while muscle load is known, which is what keeps the `sug28` baseline byte-identical.
     */
    const val RULE_MOBILITY_FOCUS: String = "MOBILITY_FOCUS"

    /** P12.3's four cycling ids (§3.5.8); only ever attached to a `CYCLE` session. */
    const val RULE_BIKE_FTP_GOAL: String = "BIKE_FTP_GOAL"
    const val RULE_BIKE_VOLUME_GOAL: String = "BIKE_VOLUME_GOAL"
    const val RULE_BIKE_EVENT_PREP: String = "BIKE_EVENT_PREP"
    const val RULE_BIKE_INDOOR_SEASON: String = "BIKE_INDOOR_SEASON"

    fun phaseRuleId(phase: TrainingPhase): String = "PHASE_${phase.name}"

    fun recoveryRuleId(band: RecoveryBand?): String = "RECOVERY_${band?.name ?: "UNKNOWN"}"

    /** The ordered rationale of one placed session. */
    fun forSession(candidate: Candidate, ctx: RationaleContext): List<RationaleEntry> {
        val entries = mutableListOf(phaseEntry(candidate.sessionType, ctx))
        ctx.downWeekReason?.let { entries += downWeekEntry(it) }
        entries += budgetEntry(ctx)
        keyEventEntry(candidate, ctx)?.let { entries += it }
        recoveryEntry(ctx)?.let { entries += it }
        capEntry(candidate.sportGroup, ctx)?.let { entries += it }
        cycleEntry(ctx)?.let { entries += it }
        bikeGoalEntry(candidate, ctx)?.let { entries += it }
        bikeIndoorEntry(candidate, ctx)?.let { entries += it }
        entries += StrengthRules.rationaleEntries(candidate, ctx)
        if (ctx.isStarterWeek) entries += starterWeekEntry()
        return entries
    }

    /** P19.6: which load-history rule made this a recovery week. */
    fun downWeekEntry(reason: DownWeekReason): RationaleEntry = RationaleEntry(
        ruleId = RULE_DOWN_WEEK,
        text = "Recovery week: " + when (reason) {
            DownWeekReason.ACUTE_OVERLOAD -> "last week's load was more than 1.5 × your usual week."
            DownWeekReason.FATIGUE -> "recovery was fatigued or strained on 3 of the last 7 days."
            DownWeekReason.BIG_BUILD -> "the last 3 weeks averaged more than 1.4 × your usual week."
            DownWeekReason.LONG_BUILD -> "5 weeks of build-up without a lighter week."
        },
    )

    /**
     * POLISH-10: the "why is this week so light" line for a brand-new athlete (§3.5.2's starter
     * target). The text itself lives in `res/values/strings.xml` (`R.string.rationale_starter_week`,
     * wired the same way the `CYCLE_*` copy is) — this literal is the domain-layer fallback, since
     * `domain/` cannot reference Android string resources (R6).
     */
    fun starterWeekEntry(): RationaleEntry = RationaleEntry(
        ruleId = RULE_STARTER_WEEK,
        text = "Starter week: no training history yet, so this is a gentle first week.",
    )

    /**
     * The `CYCLE_*` line of P11.2 — one entry per phase, or none outside the four phases the rules
     * act on. A [CycleConfidence.LOW] forecast keeps the rule but says so, because the advice then
     * rests on a default 28-day cycle rather than on the user's own history.
     */
    fun cycleEntry(ctx: RationaleContext): RationaleEntry? {
        val cycle = ctx.cycleStatus ?: return null
        val (ruleId, text) = when {
            cycle.isEarlyMenstrual -> CycleRules.RULE_MENSTRUAL_EARLY to
                "Cycle day ${cycle.dayOfCycle}: keeping the intensity moderate for the first two days."
            cycle.isMenstrual -> CycleRules.RULE_MENSTRUAL_EARLY to
                "Cycle day ${cycle.dayOfCycle}: quality work is fine now, rated a little more cautiously."
            cycle.isLateLuteal -> CycleRules.RULE_LATE_LUTEAL to
                "Late luteal phase (day ${cycle.dayOfCycle} of ~${cycle.cycleLengthDays}): " +
                "favouring recovery, and worth watching sleep and hydration this week."
            cycle.phase == CyclePhase.OVULATION -> CycleRules.RULE_OVULATION to
                "Ovulation window: take a thorough warm-up — ligaments are more lax around ovulation."
            cycle.phase == CyclePhase.FOLLICULAR -> CycleRules.RULE_FOLLICULAR to
                "Follicular phase: intervals and strength work are usually best tolerated now."
            else -> return null
        }
        return RationaleEntry(ruleId = ruleId, text = text + lowConfidenceSuffix(cycle))
    }

    /**
     * P12.3: why a ride is in the week at all — the `BIKE_*` goal it serves. Only `CYCLE` sessions
     * carry it: a strength session in a cyclist's week is not "for the FTP target".
     */
    fun bikeGoalEntry(candidate: Candidate, ctx: RationaleContext): RationaleEntry? {
        if (candidate.sportGroup != SportGroup.CYCLE) return null
        val goal = ctx.bikeGoal ?: return null
        val (ruleId, text) = when (goal.type) {
            GoalType.BIKE_FTP -> RULE_BIKE_FTP_GOAL to
                "Towards your FTP target: threshold and steady riding are what raise it."
            GoalType.BIKE_VOLUME -> RULE_BIKE_VOLUME_GOAL to
                "Counts towards your weekly riding volume."
            GoalType.BIKE_EVENT -> RULE_BIKE_EVENT_PREP to
                "Preparing for your cycling event."
            else -> return null
        }
        return RationaleEntry(ruleId = ruleId, text = text)
    }

    /** P12.3: the November–March trainer rule, whenever it actually moved a ride indoors. */
    fun bikeIndoorEntry(candidate: Candidate, ctx: RationaleContext): RationaleEntry? {
        if (!ctx.bikeIndoorSeason) return null
        if (candidate.sportGroup != SportGroup.CYCLE) return null
        if (candidate.entry.sportType != SportType.CYCLING_INDOOR) return null
        return RationaleEntry(
            ruleId = RULE_BIKE_INDOOR_SEASON,
            text = "November to March: planned indoors on the trainer.",
        )
    }

    /**
     * P14.3 (§3.11): the interval lines of a structured session — what the session actually is,
     * why it is shorter than usual in a taper, and the pace to run it at.
     *
     * All three are **appended only when there is a number to name**: a zone-only structure (no
     * VDOT, no FTP) and a session with no target pace add nothing, so every pre-P14 rationale — and
     * with it the whole `sug28` baseline — stays byte-identical.
     */
    fun intervalEntries(
        plan: IntervalPlan?,
        targetPaceSecPerKm: Int?,
        ctx: IntervalContext,
        sessionType: SessionType,
    ): List<RationaleEntry> {
        val entries = mutableListOf<RationaleEntry>()
        if (plan != null && plan.hasQuantifiedTarget) {
            entries += RationaleEntry(RULE_INTERVAL_STRUCTURE, intervalText(plan, ctx))
            if (plan.shortenedForTaper) {
                entries += RationaleEntry(
                    ruleId = RULE_INTERVAL_SHORTENED_TAPER,
                    text = "${phaseLabel(ctx.phase)}: shortened to ${plan.reps} " +
                        "${if (plan.reps == 1) "rep" else "reps"} so the legs stay fresh.",
                )
            }
        }
        if (targetPaceSecPerKm != null) {
            val zone = IntervalBuilder.recommendedZoneFor(sessionType)
            val zoneText = zone?.let { ", zone $it" } ?: ""
            entries += RationaleEntry(
                ruleId = RULE_PACE_TARGET,
                text = "Target pace ${IntervalBuilder.mmss(targetPaceSecPerKm)}/km$zoneText.",
            )
        }
        // P19: every tempo / interval run says how far the goal pace is, once a pace goal exists.
        // 0.8.1: the side goal's interval session measures itself against the side goal.
        val goal = ctx.sideGoal?.takeIf { sessionType == SessionType.INTERVAL_RUN } ?: ctx.paceGoal
        if (goal != null && sessionType in GOAL_PACE_SESSION_TYPES) {
            entries += GoalRules.goalPaceEntry(goal, usedGoalPace = plan?.usesGoalPace == true)
        }
        return entries
    }

    private fun intervalText(plan: IntervalPlan, ctx: IntervalContext): String {
        val recovery = if (plan.recoverySec > 0) {
            " with ${IntervalBuilder.mmss(plan.recoverySec)} recovery"
        } else {
            ""
        }
        val source = when {
            plan.usesGoalPace -> " — goal pace"
            plan.workPaceSecPerKm != null && ctx.vdot != null ->
                " — ${paceLabel(plan)} pace from VDOT ${vdotLabel(ctx.vdot)}"
            plan.workPowerLowW != null && ctx.ftpWatts != null -> " — from an FTP of ${ctx.ftpWatts} W"
            else -> ""
        }
        return "${plan.summary}$recovery$source."
    }

    private fun paceLabel(plan: IntervalPlan): String =
        plan.template.work.pace?.name?.lowercase() ?: "target"

    /** "50" rather than "50.0"; a measured VDOT keeps its single decimal. */
    private fun vdotLabel(vdot: Double): String =
        NumberFormat.fixed(vdot, 1).removeSuffix(".0")

    /** P11.2: LOW confidence keeps the rule and adds the "log your period" nudge. */
    private fun lowConfidenceSuffix(cycle: CycleStatus): String =
        if (cycle.confidence == CycleConfidence.LOW) {
            " Based on a default 28-day cycle — log your period to improve this."
        } else {
            ""
        }

    /** The rationale of a mobility session added by post-pass 7c. */
    /**
     * 0.3.0: the lines of an active-recovery filler (§3.5.6 step 7c). [cycleDriven] marks a spin
     * that was chosen over a run because of the menstrual/late-luteal rule.
     */
    fun forActiveRecovery(
        sessionType: SessionType,
        phase: TrainingPhase,
        afterHardDay: Boolean,
        cycleDriven: Boolean,
        isStarterWeek: Boolean = false,
    ): List<RationaleEntry> {
        val what = when (sessionType) {
            SessionType.RECOVERY_SPIN -> "an easy 30-minute spin"
            else -> "an easy 30-minute run"
        }
        val why = if (afterHardDay) {
            "the day after a hard session, $what keeps blood flowing without adding load."
        } else {
            "$what keeps the legs moving without adding load."
        }
        val entries = mutableListOf(
            RationaleEntry(ruleId = RULE_ACTIVE_RECOVERY, text = "Active recovery: $why"),
            RationaleEntry(
                ruleId = phaseRuleId(phase),
                text = "${phaseLabel(phase)}: active recovery does not count towards the weekly load.",
            ),
        )
        if (cycleDriven) {
            entries += RationaleEntry(
                ruleId = CycleRules.RULE_LATE_LUTEAL,
                text = "Cycle: an easy spin is gentler than a run on these days.",
            )
        }
        if (isStarterWeek) entries += starterWeekEntry()
        return entries
    }

    fun forMobility(phase: TrainingPhase, isStarterWeek: Boolean = false): List<RationaleEntry> {
        val entries = mutableListOf(
            RationaleEntry(
                ruleId = RULE_MOBILITY_REST_DAY,
                text = "Rest day: 20 minutes of mobility keeps the day easy and still useful.",
            ),
            RationaleEntry(
                ruleId = phaseRuleId(phase),
                text = "${phaseLabel(phase)}: no training load on rest days.",
            ),
        )
        if (isStarterWeek) entries += starterWeekEntry()
        return entries
    }

    /** Appended when post-pass 7b downgrades a session on the eve of a match or race. */
    fun downgradeEntry(label: String): RationaleEntry = RationaleEntry(
        ruleId = RULE_DOWNGRADED,
        text = "Downgraded to an easy run — $label tomorrow.",
    )

    private fun phaseEntry(sessionType: SessionType, ctx: RationaleContext): RationaleEntry {
        val goal = ctx.primaryGoalTitle?.let { " for $it" } ?: ""
        return RationaleEntry(
            ruleId = phaseRuleId(ctx.phase),
            text = "${phaseLabel(ctx.phase)}: ${sessionPurpose(sessionType)}$goal.",
        )
    }

    private fun budgetEntry(ctx: RationaleContext): RationaleEntry = RationaleEntry(
        ruleId = RULE_BUDGET,
        text = "Weekly load target ${au(ctx.weeklyTarget)} AU; ${au(ctx.remainingBudget)} AU still unallocated.",
    )

    private fun keyEventEntry(candidate: Candidate, ctx: RationaleContext): RationaleEntry? {
        val hours = ctx.hoursToKeyEvent ?: return null
        val verb = if (candidate.intensity.ordinal <= Intensity.LOW.ordinal) "Kept easy" else "Scheduled"
        return RationaleEntry(
            ruleId = RULE_C1_RESPECTED,
            text = "$verb — ${ctx.keyEventLabel} in $hours h.",
        )
    }

    private fun recoveryEntry(ctx: RationaleContext): RationaleEntry? {
        val band = ctx.recoveryBand ?: return null
        val score = ctx.recoveryScore?.let { "Recovery $it/100" } ?: "Recovery ${band.name.lowercase()}"
        val advice = when (band) {
            RecoveryBand.FRESH, RecoveryBand.GOOD -> "you can absorb a quality session"
            RecoveryBand.MODERATE -> "moderate — a steady session is the safe choice"
            RecoveryBand.FATIGUED -> "keeping the intensity down until it comes back up"
            RecoveryBand.STRAINED -> "recovery only"
        }
        return RationaleEntry(ruleId = recoveryRuleId(band), text = "$score, $advice.")
    }

    private fun capEntry(group: SportGroup, ctx: RationaleContext): RationaleEntry? {
        val cap = ctx.sportCap ?: return null
        return RationaleEntry(
            ruleId = RULE_SPORT_CAP,
            text = "${groupLabel(group)}: ${ctx.sportUsed + 1} of $cap sessions this week.",
        )
    }

    private fun au(value: Double): Int = TrimpDefaults.roundHalfUp(value)

    fun phaseLabel(phase: TrainingPhase): String = when (phase) {
        TrainingPhase.BASE -> "Base phase"
        TrainingPhase.BUILD -> "Build phase"
        TrainingPhase.PEAK -> "Peak phase"
        TrainingPhase.TAPER -> "Taper"
        TrainingPhase.RACE_WEEK -> "Race week"
        TrainingPhase.IN_SEASON -> "In season"
        TrainingPhase.OFF_SEASON -> "Off season"
        TrainingPhase.RECOVERY_WEEK -> "Recovery week"
    }

    private fun sessionPurpose(sessionType: SessionType): String = when (sessionType) {
        SessionType.EASY_RUN -> "easy running builds aerobic volume"
        SessionType.LONG_RUN -> "the long run builds endurance"
        SessionType.TEMPO_RUN -> "tempo work develops threshold"
        SessionType.INTERVAL_RUN -> "intervals sharpen top-end speed"
        SessionType.RECOVERY_RUN -> "a recovery run keeps the legs moving without cost"
        SessionType.STRENGTH_FULL -> "full-body strength supports every sport"
        SessionType.STRENGTH_UPPER -> "upper-body strength adds no leg fatigue"
        SessionType.STRENGTH_LOWER -> "heavy legs build running-specific strength"
        SessionType.SOCCER_TRAINING -> "team training keeps match sharpness"
        SessionType.SOCCER_MATCH -> "the match is the session"
        SessionType.MOBILITY -> "mobility work protects the next hard day"
        SessionType.CROSS_TRAINING -> "cross-training adds aerobic load without impact"
        SessionType.REST -> "rest is the session"
        SessionType.ENDURANCE_RIDE -> "steady riding builds aerobic volume without impact"
        SessionType.BIKE_INTERVALS -> "bike intervals raise threshold power"
        SessionType.TRAINER_SESSION -> "the trainer keeps the intensity controlled indoors"
        SessionType.RECOVERY_SPIN -> "an easy spin moves the legs without adding load"
        SessionType.TIME_TRIAL -> "a benchmark run measures your current form"
    }

    private fun groupLabel(group: SportGroup): String = when (group) {
        SportGroup.RUN -> "Running"
        SportGroup.STRENGTH -> "Strength"
        SportGroup.SOCCER -> "Soccer"
        SportGroup.CYCLE -> "Cycling"
        SportGroup.WALK -> "Walking"
        SportGroup.SWIM -> "Swimming"
        SportGroup.OTHER -> "Other"
    }
}
