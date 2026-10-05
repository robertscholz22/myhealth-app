package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.running.VdotCalculator
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.domain.model.WorkoutStepKind
import com.myhealth.domain.model.WorkoutStructureCodec
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * 0.8.1 — a short goal next to a long one ("5 km in 20:00 by 31 Dec" beside a half marathon in
 * April) gets one 1000 m interval session a week until its date, at goal pace once current form is
 * within 3 %, and strides on an easy run when two hard sessions already fill the week.
 */
class SideGoalTest {

    private val engine = SuggestionEngine(Fixtures.fixedClock("2026-09-14T06:00:00Z"))

    private val half = 21_097.5

    /** Current form for a 20:20 5 km (1.7 % off 20:00) and for a 21:58 one (9.8 % off). */
    private val closeVdot = VdotCalculator.vdot(5000.0, 1220.0)!!
    private val farVdot = VdotCalculator.vdot(5000.0, 1318.0)!!

    private fun halfGoal(daysOut: Long) =
        SuggestFixtures.raceGoal(day(daysOut), targetTimeSec = 5100, distanceMeters = half)
            .copy(title = "Berlin Half")

    private fun fiveK(daysOut: Long, isRace: Boolean = false) = SuggestFixtures.goal(
        id = 7L,
        title = "5k best",
        targetDay = day(daysOut),
        targetDistanceMeters = 5000.0,
        targetTimeSec = 1200,
        priority = 2,
    ).copy(isRace = isRace)

    private fun input(
        goals: List<Goal>,
        vdot: Double? = farVdot,
        form: GoalFormInputs? = GoalFormInputs(vdotSourceDay = day(-9), longestRunMeters28d = 12_000.0),
        lockedPlanned: List<com.myhealth.domain.model.PlannedSession> = emptyList(),
    ) = SuggestFixtures.input(
        goals = goals,
        lockedPlanned = lockedPlanned,
        profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":4,"STRENGTH":1}"""),
    ).copy(goalForm = form, vdot = vdot)

    private fun intervals(sessions: List<SuggestedSession>) =
        sessions.filter { it.sessionType == SessionType.INTERVAL_RUN }

    private fun workPaceOf(session: SuggestedSession): Int {
        val structure = WorkoutStructureCodec.decode(session.structureJson)!!
        val work = structure.steps.first { it.kind == WorkoutStepKind.REPEAT }
            .children.first { it.kind == WorkoutStepKind.WORK }
        return (work.paceLowSecPerKm!! + work.paceHighSecPerKm!!) / 2
    }

    @Test
    fun sg01_a_5k_deadline_beside_a_half_gets_one_1000m_session_a_week() {
        val result = engine.generate(input(listOf(halfGoal(202), fiveK(108))))
        assertThat(result.phase).isEqualTo(TrainingPhase.BASE)
        val session = intervals(result.sessions).single()
        assertThat(WorkoutStructureCodec.decode(session.structureJson)!!.templateId).isEqualTo("RUN_1000_I")
        assertThat(session.rationale.first { it.ruleId == GoalRules.RULE_SIDE_GOAL }.text)
            .isEqualTo("5k best: 5 km in 20:00 by 31 Dec — one 5 km-specific interval session a week until then.")
    }

    @Test
    fun sg02_far_from_the_goal_the_reps_run_at_current_interval_pace() {
        val session = intervals(engine.generate(input(listOf(halfGoal(202), fiveK(108)))).sessions).single()
        assertThat(workPaceOf(session)).isNotEqualTo(240)
        assertThat(session.rationale.first { it.ruleId == GoalRules.RULE_GOAL_PACE }.text)
            .contains("Goal pace 4:00 /km (5 km in 20:00)")
        assertThat(session.rationale.first { it.ruleId == GoalRules.RULE_GOAL_PACE }.text)
            .contains("reps stay at your current paces until you are within 3 %")
    }

    @Test
    fun sg03_within_three_percent_the_reps_run_at_5k_goal_pace() {
        val session = intervals(
            engine.generate(input(listOf(halfGoal(202), fiveK(108)), vdot = closeVdot)).sessions,
        ).single()
        assertThat(workPaceOf(session)).isEqualTo(240)
        assertThat(session.rationale.first { it.ruleId == GoalRules.RULE_GOAL_PACE }.text)
            .contains("these reps are run at goal pace")
    }

    @Test
    fun sg04_no_side_goal_after_its_date_alone_or_with_the_layer_off() {
        val past = input(listOf(halfGoal(202), fiveK(-1)))
        assertThat(GoalRules.sideGoal(past, TrainingPhase.BASE, isStarterWeek = false)).isNull()
        // A 5 k on its own is the main goal, not a side goal.
        val alone = input(listOf(fiveK(108)))
        assertThat(GoalRules.sideGoal(alone, TrainingPhase.BASE, isStarterWeek = false)).isNull()
        val off = input(listOf(halfGoal(202), fiveK(108)), form = null)
        assertThat(GoalRules.sideGoal(off, TrainingPhase.BASE, isStarterWeek = false)).isNull()
        assertThat(intervals(engine.generate(off).sessions).map { it.rationale.map { r -> r.ruleId } }.flatten())
            .doesNotContain(GoalRules.RULE_SIDE_GOAL)
    }

    @Test
    fun sg05_no_side_goal_in_taper_race_recovery_or_starter_weeks() {
        val both = input(listOf(halfGoal(202), fiveK(108)))
        listOf(TrainingPhase.TAPER, TrainingPhase.RACE_WEEK, TrainingPhase.RECOVERY_WEEK, TrainingPhase.OFF_SEASON)
            .forEach { assertThat(GoalRules.sideGoal(both, it, isStarterWeek = false)).isNull() }
        assertThat(GoalRules.sideGoal(both, TrainingPhase.BASE, isStarterWeek = true)).isNull()
        assertThat(GoalRules.sideGoal(both, TrainingPhase.BUILD, isStarterWeek = false)!!.title).isEqualTo("5k best")
    }

    @Test
    fun sg06_two_hard_sessions_already_planned_give_strides_on_an_easy_or_recovery_run() {
        val hard = listOf(
            SuggestFixtures.locked(day(1), SessionType.SOCCER_TRAINING, SportType.SOCCER_TRAINING, Intensity.HIGH, 90, 175.0),
            SuggestFixtures.locked(day(3), SessionType.SOCCER_TRAINING, SportType.SOCCER_TRAINING, Intensity.HIGH, 90, 175.0),
        )
        val sessions = engine.generate(input(listOf(halfGoal(202), fiveK(108)), lockedPlanned = hard)).sessions
        assertThat(intervals(sessions)).isEmpty()
        // Two 175-AU sessions eat the budget: only recovery runs are left, and the first carries it.
        val runs = sessions.filter { it.sportType == SportType.RUN_OUTDOOR }
        assertThat(runs).isNotEmpty()
        val withStrides = runs.filter { s -> s.rationale.any { it.ruleId == GoalRules.RULE_SIDE_GOAL } }
        assertThat(withStrides).hasSize(1)
        assertThat(withStrides.single().day).isEqualTo(runs.minOf { it.day })
        assertThat(withStrides.single().rationale.first { it.ruleId == GoalRules.RULE_SIDE_GOAL }.text)
            .contains("6 × 20 s strides at about 4:00 /km")
    }

    @Test
    fun sg07_a_due_benchmark_replaces_the_side_session_that_week() {
        val stale = input(listOf(halfGoal(202), fiveK(108)), form = GoalFormInputs(vdotSourceDay = day(-70)))
        val sessions = engine.generate(stale).sessions
        assertThat(sessions.filter { it.sessionType == SessionType.TIME_TRIAL }).hasSize(1)
        assertThat(intervals(sessions)).isEmpty()
    }

    @Test
    fun sg08_the_nearest_short_goal_wins() {
        val tenK = SuggestFixtures.goal(
            id = 8L, title = "10k", targetDay = day(60), targetDistanceMeters = 10_000.0,
            targetTimeSec = 2520, priority = 3,
        ).copy(isRace = false)
        val both = input(listOf(halfGoal(202), fiveK(108), tenK))
        assertThat(GoalRules.sideGoal(both, TrainingPhase.BASE, isStarterWeek = false)!!.title).isEqualTo("10k")
    }
}
