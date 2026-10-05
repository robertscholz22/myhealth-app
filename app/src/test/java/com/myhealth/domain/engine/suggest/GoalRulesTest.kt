package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.running.VdotCalculator
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * PLAN §P19 — the goal layer of the suggester (benchmark run, goal pace, long-run build-up, phase
 * outlook) and the checked-workout pool. Every rule is inert while `goalForm` / `strengthPool` are
 * `null`; that half is guarded by `sug28` and the older fixtures staying unchanged.
 */
class GoalRulesTest {

    private val engine = SuggestionEngine(Fixtures.fixedClock("2026-09-14T06:00:00Z"))

    private val half = 21_097.5

    private fun halfGoal(daysOut: Long, isRace: Boolean = true) =
        SuggestFixtures.raceGoal(day(daysOut), targetTimeSec = 5100, distanceMeters = half)
            .copy(title = "Berlin Half", isRace = isRace)

    private fun fiveK(daysOut: Long?, isRace: Boolean = true, priority: Int = 1, id: Long = 1L) =
        SuggestFixtures.goal(
            id = id,
            title = "5k best",
            targetDay = daysOut?.let(::day),
            targetDistanceMeters = 5000.0,
            targetTimeSec = 1200,
            priority = priority,
        ).copy(isRace = isRace)

    private fun runInput(
        goals: List<com.myhealth.domain.model.Goal>,
        form: GoalFormInputs? = GoalFormInputs(),
        vdot: Double? = null,
    ) = SuggestFixtures.input(
        goals = goals,
        profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":4,"STRENGTH":1}"""),
    ).copy(goalForm = form, vdot = vdot)

    private fun timeTrials(sessions: List<SuggestedSession>) =
        sessions.filter { it.sessionType == SessionType.TIME_TRIAL }

    // ---- recent form / VDOT ------------------------------------------------------------------

    @Test
    fun gf01_race_time_is_the_inverse_of_vdot() {
        val vdot = VdotCalculator.vdot(5000.0, 1318.0)!!
        assertThat(VdotCalculator.raceTimeSec(vdot, 5000.0)!!).isWithin(1.0).of(1318.0)
        // Half marathon from that 21:58 5 km: around 1:41 (Daniels' tables).
        assertThat(VdotCalculator.raceTimeSec(vdot, half)!!).isWithin(90.0).of(6100.0)
    }

    // ---- benchmark ---------------------------------------------------------------------------

    @Test
    fun gf02_no_recent_effort_plans_one_benchmark_5k() {
        val result = engine.generate(runInput(listOf(fiveK(108, isRace = false), halfGoal(202))))
        val tt = timeTrials(result.sessions)
        assertThat(tt).hasSize(1)
        assertThat(tt.single().targetDistanceMeters).isEqualTo(5000.0)
        assertThat(tt.single().sportType).isEqualTo(SportType.RUN_OUTDOOR)
        assertThat(tt.single().rationale.map { it.ruleId }).contains(GoalRules.RULE_BENCHMARK)
        assertThat(tt.single().rationale.first { it.ruleId == GoalRules.RULE_BENCHMARK }.text)
            .contains("stop the watch at 5.0 km")
    }

    @Test
    fun gf03_a_stale_effort_names_its_age_and_a_recent_one_needs_no_benchmark() {
        val stale = runInput(listOf(fiveK(108)), GoalFormInputs(vdotSourceDay = day(-70)), vdot = 46.0)
        val reason = GoalRules.benchmarkReason(stale, TrainingPhase.BUILD, isStarterWeek = false)
        assertThat(reason).isEqualTo("Your last hard effort was on 6 Jul, 10 weeks ago")
        // Pace = the current VDOT's 5 km time.
        val tt = timeTrials(engine.generate(stale).sessions).single()
        val expected = VdotCalculator.raceTimeSec(46.0, 5000.0)!! / 5.0
        assertThat(tt.targetPaceSecPerKm!!.toDouble()).isWithin(1.0).of(expected)

        val fresh = runInput(listOf(fiveK(108)), GoalFormInputs(vdotSourceDay = day(-9)), vdot = 46.0)
        assertThat(timeTrials(engine.generate(fresh).sessions)).isEmpty()
    }

    @Test
    fun gf04_no_benchmark_in_taper_with_a_trial_planned_or_with_the_layer_off() {
        val taper = runInput(listOf(halfGoal(9)))
        assertThat(timeTrials(engine.generate(taper).sessions)).isEmpty()

        val planned = runInput(listOf(fiveK(108)), GoalFormInputs(timeTrialDays = setOf(day(-3))))
        assertThat(timeTrials(engine.generate(planned).sessions)).isEmpty()

        val off = runInput(listOf(fiveK(108)), form = null)
        assertThat(timeTrials(engine.generate(off).sessions)).isEmpty()
    }

    // ---- long run ----------------------------------------------------------------------------

    @Test
    fun gf05_long_run_grows_ten_percent_from_the_longest_recent_run() {
        val input = runInput(listOf(halfGoal(202)), GoalFormInputs(vdotSourceDay = day(-9), longestRunMeters28d = 12_000.0))
        val plan = GoalRules.longRunPlan(input, TrainingPhase.BASE)!!
        // max(12 km × 1.10, 0.45 × HM) = 13.2 km → 13.0 km in 0.5-km steps.
        assertThat(plan.meters).isEqualTo(13_000.0)
        assertThat(plan.peakMeters).isWithin(0.01).of(18_987.75)
        assertThat(GoalRules.longRunEntry(plan).text)
            .isEqualTo("Long run 13 km — building from 12 km (your longest in 4 weeks) towards 19.0 km for Berlin Half on 4 Apr.")
        // 13 km at the 6:30 default = 84.5 → 85 min.
        assertThat(GoalRules.longRunMinutes(plan, null)).isEqualTo(85)
    }

    @Test
    fun gf06_long_run_floor_down_week_taper_race_week_and_peak_cap() {
        fun meters(daysOut: Long, base: Double, phase: TrainingPhase) = GoalRules.longRunPlan(
            runInput(listOf(halfGoal(daysOut)), GoalFormInputs(longestRunMeters28d = base)),
            phase,
        )!!.meters
        // Nothing recent: the floor, 45 % of the race (9.49 km → 9.5 km).
        assertThat(meters(202, 0.0, TrainingPhase.BASE)).isEqualTo(9_500.0)
        // Down week: 75 % of 16 km.
        assertThat(meters(100, 16_000.0, TrainingPhase.RECOVERY_WEEK)).isEqualTo(12_000.0)
        // 8–14 days out: 70 % of the peak (13.29 km → 13.5 km).
        assertThat(meters(10, 19_000.0, TrainingPhase.TAPER)).isEqualTo(13_500.0)
        // Race week: 40 % of the race.
        assertThat(meters(5, 19_000.0, TrainingPhase.RACE_WEEK)).isEqualTo(8_500.0)
        // Never beyond the peak of 90 % (18.99 km → 19 km).
        assertThat(meters(60, 20_000.0, TrainingPhase.BUILD)).isEqualTo(19_000.0)
    }

    @Test
    fun gf07_the_engine_long_run_carries_the_distance_and_its_rationale() {
        val input = runInput(listOf(halfGoal(202)), GoalFormInputs(vdotSourceDay = day(-9), longestRunMeters28d = 12_000.0))
        val longRuns = engine.generate(input).sessions.filter { it.sessionType == SessionType.LONG_RUN }
        assertThat(longRuns).isNotEmpty()
        longRuns.forEach {
            assertThat(it.targetDistanceMeters).isEqualTo(13_000.0)
            assertThat(it.targetDurationMin).isEqualTo(85)
            assertThat(it.rationale.map { r -> r.ruleId }).contains(GoalRules.RULE_LONG_RUN_BUILD)
        }
    }

    @Test
    fun gf08_a_deadline_goal_neither_tapers_nor_builds_a_long_run() {
        val deadline = halfGoal(10, isRace = false)
        assertThat(Periodization.primaryRaceGoal(listOf(deadline), SuggestFixtures.TODAY_DAY)).isNull()
        assertThat(GoalRules.longRunGoal(listOf(deadline), SuggestFixtures.TODAY_DAY)).isNull()
        assertThat(engine.generate(runInput(listOf(deadline))).batch.phase).isNotEqualTo(TrainingPhase.TAPER)
        assertThat(GoalRules.outlook(listOf(deadline), SuggestFixtures.TODAY_DAY)).isNull()
    }

    // ---- goal pace ---------------------------------------------------------------------------

    @Test
    fun gf09_goal_pace_only_within_three_percent_and_in_build_to_race_week() {
        val near = VdotCalculator.vdot(5000.0, 1225.0)!! // 2.1 % off a 20:00 goal
        val far = VdotCalculator.vdot(5000.0, 1318.0)!! // 9.8 % off
        val nearGoal = GoalRules.paceGoal(runInput(listOf(fiveK(60)), vdot = near))!!
        val farGoal = GoalRules.paceGoal(runInput(listOf(fiveK(60)), vdot = far))!!
        assertThat(nearGoal.goalPaceSecPerKm).isEqualTo(240)
        assertThat(nearGoal.withinReach).isTrue()
        assertThat(farGoal.withinReach).isFalse()

        assertThat(GoalRules.usesGoalPace(nearGoal, "RUN_1000_I", TrainingPhase.BUILD)).isTrue()
        assertThat(GoalRules.usesGoalPace(nearGoal, "RUN_1000_I", TrainingPhase.BASE)).isFalse()
        assertThat(GoalRules.usesGoalPace(nearGoal, "RUN_CRUISE_T", TrainingPhase.BUILD)).isFalse()
        assertThat(GoalRules.usesGoalPace(farGoal, "RUN_1000_I", TrainingPhase.BUILD)).isFalse()
        // The layer off → no pace goal at all.
        assertThat(GoalRules.paceGoal(runInput(listOf(fiveK(60)), form = null, vdot = near))).isNull()
    }

    @Test
    fun gf10_tempo_and_interval_runs_say_how_far_the_goal_pace_is() {
        val far = VdotCalculator.vdot(5000.0, 1318.0)!!
        val input = runInput(listOf(fiveK(60)), GoalFormInputs(vdotSourceDay = day(-9)), vdot = far)
        val hard = engine.generate(input).sessions
            .filter { it.sessionType == SessionType.TEMPO_RUN || it.sessionType == SessionType.INTERVAL_RUN }
        assertThat(hard).isNotEmpty()
        hard.forEach { session ->
            val line = session.rationale.single { it.ruleId == GoalRules.RULE_GOAL_PACE }.text
            assertThat(line).startsWith("Goal pace 4:00 /km (5 km in 20:00). Current form predicts 21:5")
            assertThat(line).endsWith("reps stay at your current paces until you are within 3 %.")
        }
    }

    // ---- outlook -----------------------------------------------------------------------------

    @Test
    fun gf11_outlook_names_the_phase_the_next_one_and_the_race() {
        // 2027-04-04 is 202 days after 2026-09-14: base now, build from 77 days before.
        val outlook = GoalRules.outlook(listOf(fiveK(108, isRace = false), halfGoal(202)), SuggestFixtures.TODAY_DAY)!!
        assertThat(outlook.phase).isEqualTo(TrainingPhase.BASE)
        assertThat(outlook.nextPhase).isEqualTo(TrainingPhase.BUILD)
        assertThat(GoalRules.dayLabel(outlook.nextPhaseDay!!)).isEqualTo("17 Jan")
        assertThat(outlook.raceGoal.title).isEqualTo("Berlin Half")
    }

    // ---- hash --------------------------------------------------------------------------------

    @Test
    fun gf12_hash_has_no_goal_or_pool_line_while_the_layers_are_off() {
        val off = runInput(listOf(fiveK(108)), form = null)
        assertThat(SuggestionInputsHash.canonical(off)).doesNotContain("goalForm=")
        assertThat(SuggestionInputsHash.canonical(off)).doesNotContain("pool=")
        val on = off.copy(goalForm = GoalFormInputs(vdotSourceDay = day(-9)))
        assertThat(SuggestionInputsHash.of(on)).isNotEqualTo(SuggestionInputsHash.of(off))
        val deadline = off.copy(goals = listOf(fiveK(108, isRace = false)))
        assertThat(SuggestionInputsHash.of(deadline)).isNotEqualTo(SuggestionInputsHash.of(off))
    }

    // ---- workout pool ------------------------------------------------------------------------

    private fun workout(id: Long, kind: StrengthWorkoutKind, name: String = "W$id") = StrengthWorkout(
        id = id,
        name = name,
        kind = kind,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    private fun poolInput(pool: List<StrengthWorkout>?, last: Map<SessionType, Long> = emptyMap()) =
        SuggestFixtures.input(
            goals = listOf(SuggestFixtures.raceGoal(day(60))),
            // All four sports, as onboarding writes them (an unlisted sport is uncapped).
            profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":2,"STRENGTH":2,"SOCCER":0,"CYCLE":0}"""),
        ).copy(
            muscleLoad = SuggestFixtures.muscleLoad(ctl = 60.0),
            strengthPool = pool,
            lastWorkoutIdBySessionType = last,
        )

    @Test
    fun wp01_pool_rotates_through_the_checked_workouts_of_a_type_by_id() {
        val pool = listOf(workout(11, StrengthWorkoutKind.UPPER), workout(10, StrengthWorkoutKind.UPPER))
        val ctx = MuscleContext.of(poolInput(pool, mapOf(SessionType.STRENGTH_UPPER to 10L)))
        assertThat(StrengthRules.choiceFor(SessionType.STRENGTH_UPPER, ctx)!!.workoutId).isEqualTo(11L)
        assertThat(StrengthRules.choiceFor(SessionType.STRENGTH_UPPER, ctx, occurrence = 1)!!.workoutId).isEqualTo(10L)
        val fresh = MuscleContext.of(poolInput(pool))
        assertThat(StrengthRules.choiceFor(SessionType.STRENGTH_UPPER, fresh)!!.workoutId).isEqualTo(10L)
        assertThat(StrengthRules.choiceFor(SessionType.STRENGTH_LOWER, fresh)).isNull()
    }

    @Test
    fun wp02_a_type_without_a_checked_workout_is_not_suggested() {
        val pool = listOf(workout(10, StrengthWorkoutKind.UPPER, "My upper"))
        assertThat(StrengthRules.excludedTypes(pool))
            .containsExactly(SessionType.STRENGTH_FULL, SessionType.STRENGTH_LOWER)
        // sug37's in-season week after a long run, where an upper-body day is the natural pick.
        val input = SuggestFixtures.input(
            events = listOf(SuggestFixtures.event(day(10), com.myhealth.domain.model.EventType.SOCCER_MATCH)),
            recentActivities = listOf(SuggestFixtures.activity(day(-1), 220.0, SportType.RUN_OUTDOOR)),
            profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":3,"STRENGTH":2}"""),
        ).copy(
            muscleLoad = SuggestFixtures.muscleLoad(sessions = listOf(SuggestFixtures.muscleSession(day(-1), trimp = 220.0))),
            strengthPool = pool,
        )
        val sessions = engine.generate(input).sessions
        val strength = sessions.filter { it.sessionType in Constraints.STRENGTH_TYPES }
        assertThat(strength).isNotEmpty()
        strength.forEach {
            assertThat(it.sessionType).isEqualTo(SessionType.STRENGTH_UPPER)
            assertThat(it.workoutId).isEqualTo(10L)
        }
    }

    @Test
    fun wp05_a_preferred_type_the_pool_cannot_serve_passes_its_preference_on() {
        val upperOnly = listOf(workout(10, StrengthWorkoutKind.UPPER))
        val build = setOf(SessionType.TEMPO_RUN, SessionType.LONG_RUN, SessionType.STRENGTH_LOWER)
        assertThat(StrengthRules.poolPreferred(build, upperOnly))
            .containsExactly(SessionType.TEMPO_RUN, SessionType.LONG_RUN, SessionType.STRENGTH_UPPER)
        assertThat(StrengthRules.poolPreferred(build, null)).isEqualTo(build)
    }

    @Test
    fun wp03_an_empty_pool_suggests_no_strength_and_no_pool_keeps_the_built_ins() {
        val none = engine.generate(poolInput(emptyList())).sessions
        assertThat(none.filter { it.sessionType in Constraints.STRENGTH_TYPES }).isEmpty()

        assertThat(StrengthRules.excludedTypes(null)).isEmpty()
        val builtIn = engine.generate(poolInput(null)).sessions
            .filter { it.sessionType in Constraints.STRENGTH_TYPES }
        assertThat(builtIn).isNotEmpty()
        builtIn.forEach {
            assertThat(it.workoutId).isNull()
            assertThat(it.workoutTemplateId).isNotNull()
        }
    }

    @Test
    fun wp04_mobility_prefers_the_muscle_state_kind_and_falls_back_to_any_routine() {
        val pool = listOf(workout(20, StrengthWorkoutKind.MOBILITY_UPPER), workout(21, StrengthWorkoutKind.MOBILITY_FULL))
        val ctx = MuscleContext.of(poolInput(pool))
        val choice = StrengthRules.choiceFor(SessionType.MOBILITY, ctx)!!
        assertThat(choice.workout!!.kind.isMobility).isTrue()
        val onlyUpper = MuscleContext.of(poolInput(listOf(workout(20, StrengthWorkoutKind.MOBILITY_UPPER))))
        assertThat(StrengthRules.choiceFor(SessionType.MOBILITY, onlyUpper)!!.workoutId).isEqualTo(20L)
    }
}
