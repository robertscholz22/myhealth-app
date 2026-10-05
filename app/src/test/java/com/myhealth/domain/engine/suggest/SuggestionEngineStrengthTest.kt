package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.myhealth.domain.engine.strength.StrengthTemplates
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.MuscleLoadBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * `sug37`…`sug41` of PLAN §3.12.5 — the strength layer as the whole engine sees it, and the two
 * cases that prove it is **invisible** without `SuggestionInput.muscleLoad`; plus P17.1's
 * `sug42`/`sug43`, which do the same for the mobility routines.
 */
class SuggestionEngineStrengthTest {

    private val engine = SuggestionEngine(Fixtures.fixedClock("2026-09-14T06:00:00Z"))

    /**
     * A 220 AU long run **yesterday**, a soccer match three weeks out (so the phase is
     * `IN_SEASON`, whose preferred types include `STRENGTH_UPPER`) and room for two strength
     * sessions a week.
     */
    private fun afterLongRun(withMuscleLoad: Boolean = true): SuggestionInput {
        val longRun = SuggestFixtures.activity(
            day = day(-1),
            trimp = 220.0,
            sportType = SportType.RUN_OUTDOOR,
        )
        val input = SuggestFixtures.input(
            events = listOf(SuggestFixtures.event(day(10), EventType.SOCCER_MATCH)),
            recentActivities = listOf(longRun),
            profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":3,"STRENGTH":2}"""),
        )
        if (!withMuscleLoad) return input
        return input.copy(
            muscleLoad = SuggestFixtures.muscleLoad(
                sessions = listOf(SuggestFixtures.muscleSession(day(-1), trimp = 220.0)),
            ),
        )
    }

    /** A `BUILD` week (race 60 days out) on untouched legs, with only two running slots. */
    private fun freshLegs(): SuggestionInput = SuggestFixtures.input(
        goals = listOf(SuggestFixtures.raceGoal(day(60))),
        // All four sports, as onboarding writes them (P19.6: an unlisted, uncapped soccer took the slot).
        profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":2,"STRENGTH":2,"SOCCER":0,"CYCLE":0}"""),
        // P19.6: CTL 35 keeps the BUILD budget near its pre-0.9.1 308 AU (35·7·1.25 ramp cap = 306);
        // with 350 AU free the heavier full-body session outscores the leg day on budget fit alone.
        recentLoad = SuggestFixtures.loadHistory(ctl = 35.0, dailyTrimp = 35.0),
    ).copy(muscleLoad = SuggestFixtures.muscleLoad(ctl = 60.0))

    private fun strengthSessions(sessions: List<SuggestedSession>): List<SuggestedSession> =
        sessions.filter { it.sessionType in Constraints.STRENGTH_TYPES }

    @Test
    fun sug37_upper_body_after_a_long_run() {
        val input = afterLongRun()
        assertThat(input.muscleLoad!!.lowerBody).isEqualTo(MuscleLoadBand.FATIGUED)
        assertThat(input.muscleLoad!!.upperBody).isEqualTo(MuscleLoadBand.FRESH)

        val result = engine.generate(input)
        val strength = strengthSessions(result.sessions)

        // The first strength day is the day after the long run, and it is an upper-body day.
        assertThat(strength).isNotEmpty()
        assertThat(strength.first().day).isEqualTo(day(1))
        assertThat(strength.first().sessionType).isEqualTo(SessionType.STRENGTH_UPPER)
        assertThat(strength.first().rationale.map { it.ruleId })
            .containsAtLeast(Rationale.RULE_MUSCLE_LOWER_LOADED, Rationale.RULE_C15_RESPECTED)

        // …and no leg day sneaks into the week at all while the legs are still loaded.
        assertThat(strength.map { it.sessionType }).containsExactly(
            SessionType.STRENGTH_UPPER,
            SessionType.STRENGTH_UPPER,
        )

        // C15 is what did it: a lower-body candidate on that same day is discarded.
        val grid = SuggestFixtures.grid(input)
        val ctx = ConstraintContext.of(input)
        val lower = SuggestFixtures.candidate(SessionType.STRENGTH_LOWER, day(1))
        assertThat(Constraints.violations(lower, lower.day, grid, ctx)).contains(ConstraintId.C15)
    }

    @Test
    fun sug38_fresh_legs_allow_strength_lower() {
        val input = freshLegs()
        assertThat(input.muscleLoad!!.lowerBody).isEqualTo(MuscleLoadBand.FRESH)

        val result = engine.generate(input)
        val strength = strengthSessions(result.sessions)

        assertThat(strength.map { it.sessionType }).contains(SessionType.STRENGTH_LOWER)
        val legDay = strength.first { it.sessionType == SessionType.STRENGTH_LOWER }
        assertThat(legDay.rationale.map { it.ruleId }).contains(Rationale.RULE_MUSCLE_LEGS_FRESH)
        assertThat(legDay.rationale.map { it.ruleId }).doesNotContain(Rationale.RULE_C15_RESPECTED)
        assertThat(legDay.workoutTemplateId).isEqualTo("LOWER_A")

        // Nothing in this week is discarded by C15 — the legs are fresh and nothing hard follows.
        val grid = SuggestFixtures.grid(input)
        val ctx = ConstraintContext.of(input)
        val candidate = SuggestFixtures.candidate(SessionType.STRENGTH_LOWER, day(0))
        assertThat(Constraints.violations(candidate, candidate.day, grid, ctx))
            .doesNotContain(ConstraintId.C15)
    }

    @Test
    fun sug39_null_muscle_load_output_unchanged() {
        // The P14.5 drift guard, and the strongest one in the file: with `muscleLoad = null` the
        // engine's **whole** rendered output for the nineteen bike-free fixtures — phase, weekly
        // target, digest, every session and every rationale line — is byte-for-byte the 0.3.0
        // baseline `sug28` pinned. `fixtures/suggest/sug28_baseline.txt` is read-only.
        assertThat(SuggestionSnapshot.renderAll(engine)).isEqualTo(SuggestionSnapshot.loadBaseline())

        // …and none of those fixtures can see a strength template or a muscle rationale line.
        val muscleRules = setOf(
            Rationale.RULE_MUSCLE_LOWER_LOADED,
            Rationale.RULE_MUSCLE_LEGS_FRESH,
            Rationale.RULE_C15_RESPECTED,
            Rationale.RULE_STRENGTH_WORKOUT,
        )
        SuggestionSnapshot.BASELINE_INPUTS.forEach { (name, input) ->
            val sessions = engine.generate(input).sessions
            assertWithMessage(name).that(sessions.mapNotNull { it.workoutTemplateId }).isEmpty()
            assertWithMessage(name)
                .that(sessions.flatMap { it.rationale }.map { it.ruleId })
                .containsNoneIn(muscleRules)
        }

        // The same week without the strength layer holds no muscle line either.
        val bare = engine.generate(afterLongRun(withMuscleLoad = false))
        assertThat(bare.sessions.flatMap { it.rationale }.map { it.ruleId })
            .containsNoneIn(muscleRules)
    }

    @Test
    fun sug40_hash_line_only_when_muscle_load_present() {
        SuggestionSnapshot.BASELINE_INPUTS.forEach { (name, input) ->
            val canonical = SuggestionInputsHash.canonical(input)
            assertWithMessage(name).that(canonical).doesNotContain("\nmuscle=")
            assertWithMessage(name).that(canonical.startsWith("muscle=")).isFalse()
        }

        val withoutLoad = afterLongRun(withMuscleLoad = false)
        val withLoad = afterLongRun()
        assertThat(SuggestionInputsHash.canonical(withoutLoad)).doesNotContain("muscle=")
        assertThat(SuggestionInputsHash.canonical(withLoad)).contains("\nmuscle=")

        // The digest is unchanged for `null` and moves once the load is known.
        assertThat(SuggestionInputsHash.of(withoutLoad))
            .isEqualTo(SuggestionInputsHash.of(withoutLoad.copy(muscleLoad = null)))
        assertThat(SuggestionInputsHash.of(withLoad)).isNotEqualTo(SuggestionInputsHash.of(withoutLoad))

        // …and a changed muscle load regenerates the week, which is the point of hashing it.
        val lighter = withLoad.copy(
            muscleLoad = SuggestFixtures.muscleLoad(
                sessions = listOf(SuggestFixtures.muscleSession(day(-1), trimp = 60.0)),
            ),
        )
        assertThat(SuggestionInputsHash.of(lighter)).isNotEqualTo(SuggestionInputsHash.of(withLoad))
    }

    @Test
    fun sug41_strength_session_names_a_template() {
        val templateIds = StrengthTemplates.ALL.mapNotNull { it.templateId }
        val strength = strengthSessions(engine.generate(afterLongRun()).sessions)

        assertThat(strength).hasSize(2)
        strength.forEach { session ->
            assertThat(session.workoutTemplateId).isIn(templateIds)
            assertThat(session.rationale.map { it.ruleId }).contains(Rationale.RULE_STRENGTH_WORKOUT)
        }
        // The two upper days alternate rather than repeating the same workout (§3.12.5).
        assertThat(strength.map { it.workoutTemplateId }).containsExactly("UPPER_A", "UPPER_B").inOrder()

        // The rationale names the workout the template actually is: "Upper A — 6 exercises, 44 min".
        val line = strength.first().rationale.first { it.ruleId == Rationale.RULE_STRENGTH_WORKOUT }
        assertThat(line.text).isEqualTo("Workout: Upper A — 6 exercises, about 44 min.")

        // The alternation starts from the last accepted template: after UPPER_A comes UPPER_B.
        val continued = afterLongRun().copy(
            lastAcceptedTemplateByKind = mapOf(
                com.myhealth.domain.model.StrengthWorkoutKind.UPPER to "UPPER_A",
            ),
        )
        assertThat(strengthSessions(engine.generate(continued).sessions).map { it.workoutTemplateId })
            .containsExactly("UPPER_B", "UPPER_A").inOrder()

        // Nothing that is not a strength session ever names a template.
        engine.generate(afterLongRun()).sessions
            .filterNot { it.sessionType in Constraints.STRENGTH_TYPES }
            .forEach { assertThat(it.workoutTemplateId).isNull() }
    }

    @Test
    fun sug42_rest_day_mobility_names_a_routine() {
        // The same loaded-legs week, with `mobilityOnRestDays` on: post-pass 7d fills every rest
        // day with mobility, and P17.1 gives each filler the routine the muscle state asks for.
        val input = afterLongRun().copy(
            profile = SuggestFixtures.profile(
                preferredSportsJson = """{"RUN":3,"STRENGTH":2}""",
                mobilityOnRestDays = true,
            ),
        )
        val load = requireNotNull(input.muscleLoad)
        assertThat(load.lowerBody).isEqualTo(MuscleLoadBand.FATIGUED)
        assertThat(load.upperBody).isEqualTo(MuscleLoadBand.FRESH)

        val mobility = engine.generate(input).sessions.filter { it.sessionType == SessionType.MOBILITY }
        assertThat(mobility).isNotEmpty()
        mobility.forEach { session ->
            assertThat(session.workoutTemplateId).isEqualTo("MOBILITY_LOWER_A")
            val line = session.rationale.first { it.ruleId == Rationale.RULE_MOBILITY_FOCUS }
            assertThat(line.text).isEqualTo(
                "Mobility: legs are loaded, so this routine targets hips, hamstrings and calves.",
            )
            // The mobility routine is announced by its own line, never by the strength one.
            assertThat(session.rationale.map { it.ruleId })
                .doesNotContain(Rationale.RULE_STRENGTH_WORKOUT)
            // …and it is still a rest-day filler: the rest-day rationale is untouched.
            assertThat(session.rationale.map { it.ruleId }).contains(Rationale.RULE_MOBILITY_REST_DAY)
        }

        // The routine is materialisable, which is what `accept` does with it (P14.5 / P17.1).
        val routine = requireNotNull(StrengthTemplates.byId("MOBILITY_LOWER_A"))
        assertThat(routine.kind.isMobility).isTrue()
        assertThat(routine.estimatedMinutes).isEqualTo(21)

        // Without the muscle layer the very same week names nothing and says nothing (`sug39`).
        val bare = input.copy(muscleLoad = null)
        engine.generate(bare).sessions
            .filter { it.sessionType == SessionType.MOBILITY }
            .forEach { session ->
                assertThat(session.workoutTemplateId).isNull()
                assertThat(session.rationale.map { it.ruleId })
                    .doesNotContain(Rationale.RULE_MOBILITY_FOCUS)
            }
    }

    @Test
    fun sug43_baselines_unchanged() {
        // P17.1's drift guard: the mobility catalog, the three routines and the `MOBILITY_FOCUS`
        // line are additions that a muscle-load-free week cannot see. `sug28_baseline.txt` is
        // read-only and was *not* regenerated for P17.
        assertThat(SuggestionSnapshot.renderAll(engine)).isEqualTo(SuggestionSnapshot.loadBaseline())

        SuggestionSnapshot.BASELINE_INPUTS.forEach { (name, input) ->
            val sessions = engine.generate(input).sessions
            assertWithMessage(name).that(sessions.mapNotNull { it.workoutTemplateId }).isEmpty()
            assertWithMessage(name)
                .that(sessions.flatMap { it.rationale }.map { it.ruleId })
                .doesNotContain(Rationale.RULE_MOBILITY_FOCUS)
        }

        // Five of those fixtures really do hold mobility sessions — the guard is not vacuous.
        val withMobility = SuggestionSnapshot.BASELINE_INPUTS.count { (_, input) ->
            engine.generate(input).sessions.any { it.sessionType == SessionType.MOBILITY }
        }
        assertThat(withMobility).isAtLeast(1)
    }
}
