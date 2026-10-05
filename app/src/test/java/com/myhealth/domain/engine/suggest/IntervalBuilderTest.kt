package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.domain.model.WorkoutStep
import com.myhealth.domain.model.WorkoutStepKind
import com.myhealth.domain.model.WorkoutStructure
import com.myhealth.domain.model.WorkoutStructureCodec
import com.myhealth.domain.model.WorkoutTargetKind
import org.junit.Test

/**
 * [IntervalBuilder] against the values of PLAN §3.11 (`iv01`…`iv07`, `iv10`…`iv13`; `iv08`/`iv09`
 * are the codec's and live in `WorkoutStructureTest`).
 *
 * Two §3.11 values could not be reproduced as written and are asserted at the value the plan's own
 * rules produce (both recorded as §3.11 notes):
 * - `iv03`: rule 2's `reps = max(minReps, roundHalfUp(reps × 0.6))` floors `RUN_1000_I`'s 5 reps at
 *   its `minReps` of **4**, not at the 3 the table quotes;
 * - `iv07`: `2 × (10 × 30/30)` cannot be a `REPEAT` inside a `REPEAT` — the P14.1 codec allows
 *   exactly one level of nesting — so the inner ten live as `repeat = 10` on the outer repeat's two
 *   children.
 */
class IntervalBuilderTest {

    private val intervalRun = SuggestFixtures.candidate(SessionType.INTERVAL_RUN, day(3), minutes = 55)

    private fun ctx(
        phase: TrainingPhase = TrainingPhase.BUILD,
        vdot: Double? = 50.0,
        goalDistanceMeters: Double? = null,
        ftpWatts: Int? = null,
        acwr: Double? = null,
        recoveryBand: RecoveryBand? = null,
    ) = IntervalContext(
        phase = phase,
        goalDistanceMeters = goalDistanceMeters,
        vdot = vdot,
        ftpWatts = ftpWatts,
        acwr = acwr,
        recoveryBand = recoveryBand,
    )

    private fun workStepOf(structure: WorkoutStructure): WorkoutStep {
        val repeat = structure.steps.firstOrNull { it.kind == WorkoutStepKind.REPEAT }
        return repeat?.children?.first { it.kind == WorkoutStepKind.WORK }
            ?: structure.steps.first { it.kind == WorkoutStepKind.WORK }
    }

    @Test
    fun iv01_five_by_1000_from_vdot_50() {
        val plan = IntervalBuilder.plan(intervalRun, ctx())!!

        assertThat(plan.template.id).isEqualTo("RUN_1000_I")
        assertThat(plan.reps).isEqualTo(5)
        assertThat(plan.workPaceSecPerKm).isEqualTo(234)
        val work = workStepOf(plan.structure)
        assertThat(work.distanceMeters).isEqualTo(1_000.0)
        assertThat(work.target).isEqualTo(WorkoutTargetKind.PACE)
        assertThat(work.paceLowSecPerKm).isEqualTo(229)
        assertThat(work.paceHighSecPerKm).isEqualTo(239)
        assertThat(work.zone).isEqualTo(5)
        assertThat(plan.summary).isEqualTo("5 × 1000 m @ 3:54")
        assertThat(plan.structure.templateId).isEqualTo("RUN_1000_I")
    }

    @Test
    fun iv02_reps_fit_the_placed_minutes() {
        val plan = IntervalBuilder.plan(intervalRun, ctx())!!

        // 55 min − 15 warm-up − 10 cool-down = 1800 s of work budget; 5 × (234 s + 120 s) = 1770 s.
        assertThat(plan.workSec).isEqualTo(234)
        assertThat(plan.recoverySec).isEqualTo(120)
        assertThat(plan.reps * (plan.workSec + plan.recoverySec)).isEqualTo(1_770)
        assertThat(plan.reps).isEqualTo(5)
        assertThat(plan.structure.steps.first().kind).isEqualTo(WorkoutStepKind.WARMUP)
        assertThat(plan.structure.steps.first().durationSec).isEqualTo(900)
        assertThat(plan.structure.steps.last().kind).isEqualTo(WorkoutStepKind.COOLDOWN)
        assertThat(plan.structure.steps.last().durationSec).isEqualTo(600)
    }

    @Test
    fun iv03_taper_shortens_to_the_min_reps_floor() {
        val full = IntervalBuilder.plan(intervalRun, ctx())!!
        val tapered = IntervalBuilder.plan(intervalRun, ctx(phase = TrainingPhase.RACE_WEEK))!!

        assertThat(full.reps).isEqualTo(5)
        // roundHalfUp(5 × 0.6) = 3, floored at RUN_1000_I's minReps = 4 (§3.11 note).
        assertThat(tapered.reps).isEqualTo(4)
        assertThat(tapered.shortenedForTaper).isTrue()
        val entries = Rationale.intervalEntries(
            plan = tapered,
            targetPaceSecPerKm = null,
            ctx = ctx(phase = TrainingPhase.RACE_WEEK),
            sessionType = SessionType.INTERVAL_RUN,
        )
        assertThat(entries.map { it.ruleId }).contains(Rationale.RULE_INTERVAL_SHORTENED_TAPER)
    }

    @Test
    fun iv04_high_acwr_uses_min_reps() {
        val plan = IntervalBuilder.plan(intervalRun, ctx(acwr = 1.4))!!
        assertThat(plan.reps).isEqualTo(plan.template.minReps)
        assertThat(plan.reps).isEqualTo(4)

        val fatigued = IntervalBuilder.plan(intervalRun, ctx(recoveryBand = RecoveryBand.FATIGUED))!!
        assertThat(fatigued.reps).isEqualTo(4)
    }

    @Test
    fun iv05_no_vdot_is_zone_only() {
        val plan = IntervalBuilder.plan(intervalRun, ctx(vdot = null))!!

        // A distance rep needs a pace to become seconds, so the time-based 4×4 stands in for it.
        assertThat(plan.template.id).isEqualTo("RUN_4X4")
        assertThat(plan.workPaceSecPerKm).isNull()
        val repeat = plan.structure.steps.first { it.kind == WorkoutStepKind.REPEAT }
        val work = repeat.children.first { it.kind == WorkoutStepKind.WORK }
        assertThat(work.target).isEqualTo(WorkoutTargetKind.ZONE)
        assertThat(work.zone).isEqualTo(5)
        assertThat(work.paceLowSecPerKm).isNull()
        assertThat(work.paceHighSecPerKm).isNull()
        assertThat(work.powerLowW).isNull()
        assertThat(plan.summary).isEqualTo("${plan.reps} × 4:00 in Z5")
    }

    @Test
    fun iv06_bike_4x8_at_285w() {
        val candidate = SuggestFixtures.candidate(SessionType.BIKE_INTERVALS, day(2))
        val plan = IntervalBuilder.plan(candidate, ctx(vdot = null, ftpWatts = 285))!!

        assertThat(plan.template.id).isEqualTo("BIKE_4X8_FTP")
        assertThat(plan.workPowerLowW).isEqualTo(271)
        assertThat(plan.workPowerHighW).isEqualTo(299)
        val work = workStepOf(plan.structure)
        assertThat(work.target).isEqualTo(WorkoutTargetKind.POWER)
        assertThat(work.durationSec).isEqualTo(480)
        assertThat(plan.summary).contains("271–299 W")
    }

    @Test
    fun iv07_thirty_thirty_is_one_nesting_level() {
        val candidate = SuggestFixtures.candidate(SessionType.BIKE_INTERVALS, day(2), minutes = 40)
        val plan = IntervalBuilder.plan(
            candidate,
            ctx(phase = TrainingPhase.IN_SEASON, vdot = null, ftpWatts = 285),
        )!!

        assertThat(plan.template.id).isEqualTo("BIKE_30_30")
        val outer = plan.structure.steps.first { it.kind == WorkoutStepKind.REPEAT }
        assertThat(outer.repeat).isEqualTo(2)
        assertThat(outer.children).hasSize(2)
        assertThat(outer.children.map { it.repeat }).containsExactly(10, 10)
        assertThat(outer.children.first().powerLowW).isEqualTo(371)
        assertThat(outer.children.last().powerLowW).isEqualTo(143)
        assertThat(WorkoutStructureCodec.isNestingValid(plan.structure)).isTrue()
        assertThat(plan.summary).isEqualTo("2 × (10 × 0:30) @ 371 W")

        // A third level is rejected by the codec, whatever produced it.
        val deeper = WorkoutStructure(
            steps = listOf(outer.copy(children = listOf(outer.children.first().copy(children = outer.children)))),
        )
        assertThat(WorkoutStructureCodec.isNestingValid(deeper)).isFalse()
        assertThat(WorkoutStructureCodec.decode(WorkoutStructureCodec.encode(deeper))).isNull()
    }

    @Test
    fun iv10_half_marathon_goal_prefers_cruise_intervals() {
        val plan = IntervalBuilder.plan(intervalRun, ctx(goalDistanceMeters = 21_097.5))!!

        assertThat(plan.template.id).isEqualTo("RUN_CRUISE_T")
        assertThat(plan.workPaceSecPerKm).isEqualTo(255)
        assertThat(workStepOf(plan.structure).zone).isEqualTo(4)
    }

    @Test
    fun iv11_five_k_goal_in_peak_prefers_400s() {
        val plan = IntervalBuilder.plan(
            intervalRun,
            ctx(phase = TrainingPhase.PEAK, goalDistanceMeters = 5_000.0),
        )!!

        assertThat(plan.template.id).isEqualTo("RUN_400_R")
        assertThat(plan.workPaceSecPerKm).isEqualTo(219)
        assertThat(workStepOf(plan.structure).distanceMeters).isEqualTo(400.0)
    }

    @Test
    fun iv12_work_distance_capped_at_six_km() {
        val long = SuggestFixtures.candidate(SessionType.INTERVAL_RUN, day(3), minutes = 200)
        val plan = IntervalBuilder.plan(long, ctx())!!

        assertThat(plan.template.id).isEqualTo("RUN_1000_I")
        assertThat(plan.reps).isAtMost(6)
        assertThat(plan.reps * 1_000.0).isAtMost(IntervalBuilder.MAX_WORK_DISTANCE_M)
        assertThat(plan.summary).doesNotContain("12 ×")
    }

    @Test
    fun iv13_only_four_session_types_get_a_structure() {
        val without = listOf(
            SessionType.EASY_RUN,
            SessionType.LONG_RUN,
            SessionType.RECOVERY_RUN,
            SessionType.STRENGTH_FULL,
            SessionType.STRENGTH_UPPER,
            SessionType.STRENGTH_LOWER,
            SessionType.MOBILITY,
            SessionType.CROSS_TRAINING,
            SessionType.ENDURANCE_RIDE,
            SessionType.RECOVERY_SPIN,
        )
        without.forEach { type ->
            val candidate = SuggestFixtures.candidate(type, day(1))
            assertThat(IntervalBuilder.buildFor(candidate, ctx(ftpWatts = 285))).isNull()
        }
        IntervalBuilder.STRUCTURED_TYPES.forEach { type ->
            val candidate = SuggestFixtures.candidate(type, day(1))
            assertThat(IntervalBuilder.buildFor(candidate, ctx(ftpWatts = 285))).isNotNull()
        }
    }
}
