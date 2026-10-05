package com.myhealth.domain.engine.suggest

import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import java.util.Locale

/**
 * The `sug28` drift guard (P12.3): a text rendering of the engine's **whole** output for every
 * fixture the `sug01`…`sug26` cases run on, compared against a snapshot taken from the engine as it
 * stood *before* the cycling work (`fixtures/suggest/sug28_baseline.txt`).
 *
 * The baseline file was produced by running [renderAll] against that pre-change engine and writing
 * the result verbatim; from then on it is read-only. Cycling must be completely inert for a profile
 * with no `CYCLE` cap and no `BIKE_*` goal — including the `inputsHash`, which is why the two new
 * profile fields are only serialized when they are set (see [SuggestionInputsHash]).
 *
 * The rendering is deliberately exhaustive (day, sport, type, intensity, minutes, load, score and
 * every rationale line) so a drift shows up as a readable diff rather than as "a list changed".
 */
object SuggestionSnapshot {

    const val RESOURCE_PATH: String = "fixtures/suggest/sug28_baseline.txt"

    /** The bike-free fixtures of §3.5.7 / P11.2, each under the name the baseline file uses. */
    val BASELINE_INPUTS: List<Pair<String, SuggestionInput>> = listOf(
        "sug01_match_day3" to SuggestFixtures.input(
            events = listOf(SuggestFixtures.event(day(3), EventType.SOCCER_MATCH)),
        ),
        "sug03_match_today_mobility" to SuggestFixtures.input(
            events = listOf(SuggestFixtures.event(day(0), EventType.SOCCER_MATCH)),
            profile = SuggestFixtures.profile(mobilityOnRestDays = true),
        ),
        "sug04_plain_week" to SuggestFixtures.input(),
        "sug05_busy_history" to SuggestFixtures.input(
            recentLoad = SuggestFixtures.loadHistory(ctl = 80.0, dailyTrimp = 80.0),
        ),
        "sug06_strained" to SuggestFixtures.input(
            recovery = SuggestFixtures.recovery(RecoveryBand.STRAINED, score = 28),
        ),
        "sug07_high_acwr" to SuggestFixtures.input(recentLoad = SuggestFixtures.loadHistory(acwr = 1.7)),
        "sug08_blocked_day" to SuggestFixtures.input(
            events = listOf(SuggestFixtures.event(day(2), EventType.BLOCKED)),
            profile = SuggestFixtures.profile(mobilityOnRestDays = true),
        ),
        "sug13_run_cap_two" to SuggestFixtures.input(
            profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":2}"""),
        ),
        "sug14_mixed_week" to SuggestFixtures.input(
            goals = listOf(SuggestFixtures.raceGoal(day(40))),
            events = listOf(SuggestFixtures.event(day(4), EventType.SOCCER_TRAINING)),
            recovery = SuggestFixtures.recovery(RecoveryBand.GOOD),
            profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":4,"STRENGTH":2}"""),
        ),
        "sug15_race_goal_mobility" to SuggestFixtures.input(
            goals = listOf(SuggestFixtures.raceGoal(day(30))),
            recovery = SuggestFixtures.recovery(RecoveryBand.GOOD, score = 72),
            profile = SuggestFixtures.profile(mobilityOnRestDays = true),
        ),
        "sug18_mobility_enabled" to SuggestFixtures.input(
            profile = SuggestFixtures.profile(mobilityOnRestDays = true),
        ),
        "sug19_two_week_horizon" to SuggestFixtures.input(
            horizonDays = 14,
            recentLoad = SuggestFixtures.loadHistory(ctl = 70.0, dailyTrimp = 70.0),
        ),
        "sug26_starter_week" to SuggestFixtures.input(
            recentLoad = emptyList(),
            profile = SuggestFixtures.profile(mobilityOnRestDays = true),
        ),
        "locked_tempo_day2" to SuggestFixtures.input(
            lockedPlanned = listOf(
                SuggestFixtures.locked(day(2), SessionType.TEMPO_RUN, intensity = Intensity.HIGH),
            ),
        ),
        "sug21_cycle_day1" to cycleInput(SuggestFixtures.cycleStatuses(offset = 0)),
        "sug22_follicular" to cycleInput(SuggestFixtures.cycleStatuses(offset = -5)),
        "sug23_ovulation" to cycleInput(SuggestFixtures.cycleStatuses(offset = -12)),
        "sug24_late_luteal" to cycleInput(
            SuggestFixtures.cycleStatuses(offset = -23, horizonDays = 5),
            horizonDays = 5,
        ),
        "sug25_no_cycle_data" to cycleInput(emptyMap()),
    )

    /** The `sug21`…`sug25` fixture: a 5k race 30 days out on a busy history, plus cycle statuses. */
    private fun cycleInput(
        cycle: Map<Long, com.myhealth.domain.model.CycleStatus>,
        horizonDays: Int = 7,
    ): SuggestionInput = SuggestFixtures.input(
        horizonDays = horizonDays,
        goals = listOf(SuggestFixtures.raceGoal(day(30))),
        recentLoad = SuggestFixtures.loadHistory(ctl = 80.0, dailyTrimp = 80.0),
        cycleStatusByDay = cycle,
    )

    /** Every fixture's full output, in one deterministic block of text. */
    fun renderAll(engine: SuggestionEngine): String = buildString {
        BASELINE_INPUTS.forEach { (name, input) ->
            append(render(name, engine.generate(input)))
        }
    }

    fun render(name: String, result: SuggestionResult): String = buildString {
        appendLine("== $name")
        appendLine("phase=${result.phase}")
        appendLine("weeklyTarget=${num(result.weeklyTarget)}")
        appendLine("inputsHash=${result.inputsHash}")
        result.sessions.forEach { session -> append(renderSession(session)) }
        appendLine()
    }

    private fun renderSession(session: SuggestedSession): String = buildString {
        appendLine(
            listOf(
                "day+${session.day - SuggestFixtures.TODAY_DAY}",
                session.sportType.name,
                session.sessionType.name,
                session.intensity.name,
                "${session.targetDurationMin}min",
                "${num(session.estimatedTrimp)}au",
                "score=${num(session.score)}",
                "status=${session.status}",
            ).joinToString(" | ", prefix = "  "),
        )
        session.rationale.forEach { appendLine("    - ${it.ruleId}: ${it.text}") }
    }

    private fun num(value: Double): String = String.format(Locale.US, "%.6f", value)

    /** Loads the baseline off the classpath (rule R12: `user.dir` is `app/` under Gradle). */
    fun loadBaseline(): String {
        val url = checkNotNull(SuggestionEngine::class.java.classLoader).getResource(RESOURCE_PATH)
            ?: error("Missing suggestion baseline on the classpath: $RESOURCE_PATH")
        return url.readText()
    }
}
