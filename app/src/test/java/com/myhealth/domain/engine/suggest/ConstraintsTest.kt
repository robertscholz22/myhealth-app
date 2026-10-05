package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.engine.suggest.SuggestFixtures.candidate
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import org.junit.Test

/**
 * One named test per hard constraint of PLAN §3.5.3 (`c01`…`c12`, risk R13's lock-down), plus
 * `cat01` for the §3.5.4 catalog's computed `estTrimp`.
 *
 * Each test asserts the constraint fires where the plan says it must **and** does not fire on the
 * neighbouring case, which is what keeps the rules from being trivially always-on.
 */
class ConstraintsTest {

    private fun violations(candidate: Candidate, input: SuggestionInput): List<ConstraintId> =
        Constraints.violations(candidate, candidate.day, SuggestionGrid.seed(input), ConstraintContext.of(input))

    // ---- C1 / C2: the 48 h window before a match or race ------------------------------------------

    @Test
    fun c01_no_high_within_48h_before_match() {
        val input = SuggestFixtures.input(events = listOf(SuggestFixtures.event(day(3), EventType.SOCCER_MATCH)))

        listOf(1L, 2L, 3L).forEach { offset ->
            assertThat(violations(candidate(SessionType.TEMPO_RUN, day(offset)), input))
                .contains(ConstraintId.C1)
        }
        assertThat(violations(candidate(SessionType.TEMPO_RUN, day(0)), input)).doesNotContain(ConstraintId.C1)
        assertThat(violations(candidate(SessionType.EASY_RUN, day(2)), input)).doesNotContain(ConstraintId.C1)
    }

    @Test
    fun c02_no_lower_body_strength_before_match() {
        val input = SuggestFixtures.input(events = listOf(SuggestFixtures.event(day(3), EventType.SOCCER_MATCH)))

        assertThat(violations(candidate(SessionType.STRENGTH_LOWER, day(2)), input)).contains(ConstraintId.C2)
        assertThat(violations(candidate(SessionType.STRENGTH_FULL, day(1)), input)).contains(ConstraintId.C2)
        assertThat(violations(candidate(SessionType.STRENGTH_UPPER, day(2)), input))
            .doesNotContain(ConstraintId.C2)
        assertThat(violations(candidate(SessionType.STRENGTH_LOWER, day(0)), input))
            .doesNotContain(ConstraintId.C2)
    }

    // ---- C3: a rest day in every rolling week ------------------------------------------------------

    @Test
    fun c03_at_least_one_free_day_per_week() {
        val busy = (0L..5L).map { SuggestFixtures.locked(day(it)) }
        val input = SuggestFixtures.input(lockedPlanned = busy)

        // Day +6 is the week's only remaining rest day: filling it breaks C3.
        assertThat(violations(candidate(SessionType.EASY_RUN, day(6)), input)).contains(ConstraintId.C3)
        // Mobility never consumes the rest day.
        assertThat(violations(candidate(SessionType.MOBILITY, day(6)), input)).doesNotContain(ConstraintId.C3)

        val quiet = SuggestFixtures.input(lockedPlanned = busy.take(3))
        assertThat(violations(candidate(SessionType.EASY_RUN, day(6)), quiet)).doesNotContain(ConstraintId.C3)
    }

    // ---- C4: the day after a match, race or 200 AU day ---------------------------------------------

    @Test
    fun c04_day_after_match_is_recovery_only() {
        val input = SuggestFixtures.input(events = listOf(SuggestFixtures.event(day(2), EventType.SOCCER_MATCH)))

        assertThat(violations(candidate(SessionType.LONG_RUN, day(3)), input)).contains(ConstraintId.C4)
        assertThat(violations(candidate(SessionType.RECOVERY_RUN, day(3)), input))
            .doesNotContain(ConstraintId.C4)
        assertThat(violations(candidate(SessionType.MOBILITY, day(3)), input)).doesNotContain(ConstraintId.C4)

        // …and the same rule fires after a completed 200 AU session, with no event involved.
        val afterBigDay = SuggestFixtures.input(
            recentActivities = listOf(SuggestFixtures.activity(day(-1), trimp = 230.0)),
        )
        assertThat(violations(candidate(SessionType.TEMPO_RUN, day(0)), afterBigDay))
            .contains(ConstraintId.C4)
    }

    // ---- C5: two hard sessions per rolling week ----------------------------------------------------

    @Test
    fun c05_max_two_high_sessions_per_week() {
        val hard = listOf(
            SuggestFixtures.locked(day(0), SessionType.TEMPO_RUN, intensity = Intensity.HIGH),
            SuggestFixtures.locked(day(3), SessionType.TEMPO_RUN, intensity = Intensity.HIGH, id = 99L),
        )
        val input = SuggestFixtures.input(lockedPlanned = hard)

        assertThat(violations(candidate(SessionType.INTERVAL_RUN, day(6)), input)).contains(ConstraintId.C5)
        assertThat(violations(candidate(SessionType.EASY_RUN, day(6)), input)).doesNotContain(ConstraintId.C5)

        val onlyOne = SuggestFixtures.input(lockedPlanned = hard.take(1))
        assertThat(violations(candidate(SessionType.INTERVAL_RUN, day(6)), onlyOne))
            .doesNotContain(ConstraintId.C5)
    }

    // ---- C6 / C7: blocked days, locked sessions, one session per day -------------------------------

    @Test
    fun c06_blocked_day_gets_no_suggestion() {
        val blocked = SuggestFixtures.input(events = listOf(SuggestFixtures.event(day(1), EventType.BLOCKED)))
        assertThat(violations(candidate(SessionType.EASY_RUN, day(1)), blocked)).contains(ConstraintId.C6)
        assertThat(violations(candidate(SessionType.MOBILITY, day(1)), blocked)).contains(ConstraintId.C6)
        assertThat(violations(candidate(SessionType.EASY_RUN, day(2)), blocked)).doesNotContain(ConstraintId.C6)

        val locked = SuggestFixtures.input(lockedPlanned = listOf(SuggestFixtures.locked(day(1))))
        assertThat(violations(candidate(SessionType.TEMPO_RUN, day(1)), locked)).contains(ConstraintId.C6)
        // A mobility session may still join a locked non-mobility session (C7's second-session rule).
        assertThat(violations(candidate(SessionType.MOBILITY, day(1)), locked)).doesNotContain(ConstraintId.C6)
    }

    @Test
    fun c07_one_non_mobility_session_per_day() {
        val input = SuggestFixtures.input(
            events = listOf(SuggestFixtures.event(day(1), EventType.SOCCER_TRAINING)),
        )
        assertThat(violations(candidate(SessionType.EASY_RUN, day(1)), input)).contains(ConstraintId.C7)
        assertThat(violations(candidate(SessionType.MOBILITY, day(1)), input)).doesNotContain(ConstraintId.C7)
        assertThat(violations(candidate(SessionType.EASY_RUN, day(2)), input)).doesNotContain(ConstraintId.C7)
    }

    // ---- C8 / C9: the athlete's current state ------------------------------------------------------

    @Test
    fun c08_fatigued_and_strained_recovery_limit_today() {
        val fatigued = SuggestFixtures.input(recovery = SuggestFixtures.recovery(RecoveryBand.FATIGUED))
        assertThat(violations(candidate(SessionType.TEMPO_RUN, day(0)), fatigued)).contains(ConstraintId.C8)
        assertThat(violations(candidate(SessionType.RECOVERY_RUN, day(0)), fatigued))
            .doesNotContain(ConstraintId.C8)
        assertThat(violations(candidate(SessionType.TEMPO_RUN, day(1)), fatigued))
            .doesNotContain(ConstraintId.C8)

        val strained = SuggestFixtures.input(recovery = SuggestFixtures.recovery(RecoveryBand.STRAINED))
        assertThat(violations(candidate(SessionType.RECOVERY_RUN, day(0)), strained)).contains(ConstraintId.C8)
        assertThat(violations(candidate(SessionType.MOBILITY, day(0)), strained)).contains(ConstraintId.C8)
    }

    @Test
    fun c09_high_acwr_suppresses_high_intensity() {
        val spiking = SuggestFixtures.input(recentLoad = SuggestFixtures.loadHistory(acwr = 1.7))
        (0L..6L).forEach { offset ->
            assertThat(violations(candidate(SessionType.INTERVAL_RUN, day(offset)), spiking))
                .contains(ConstraintId.C9)
        }
        assertThat(violations(candidate(SessionType.EASY_RUN, day(3)), spiking)).doesNotContain(ConstraintId.C9)

        val calm = SuggestFixtures.input(recentLoad = SuggestFixtures.loadHistory(acwr = 1.4))
        assertThat(violations(candidate(SessionType.INTERVAL_RUN, day(3)), calm)).doesNotContain(ConstraintId.C9)
    }

    // ---- C10 / C11 / C12: caps, spacing, weekday --------------------------------------------------

    @Test
    fun c10_per_sport_weekly_cap_counts_fixed_sessions() {
        val input = SuggestFixtures.input(
            profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":2,"STRENGTH":2}"""),
            lockedPlanned = listOf(
                SuggestFixtures.locked(day(0)),
                SuggestFixtures.locked(day(2), id = 77L),
            ),
        )
        assertThat(violations(candidate(SessionType.EASY_RUN, day(4)), input)).contains(ConstraintId.C10)
        // A different sport is unaffected by the run cap.
        assertThat(violations(candidate(SessionType.STRENGTH_UPPER, day(4)), input))
            .doesNotContain(ConstraintId.C10)

        val noCaps = SuggestFixtures.input(
            lockedPlanned = listOf(SuggestFixtures.locked(day(0)), SuggestFixtures.locked(day(2), id = 77L)),
        )
        assertThat(violations(candidate(SessionType.EASY_RUN, day(4)), noCaps)).doesNotContain(ConstraintId.C10)
    }

    @Test
    fun c10b_all_zero_caps_means_no_cap() {
        // POLISH-11: a skipped onboarding step (or a profile cleared back to all zeros) must not
        // ban every sport outright — that is read as "no caps configured", not "no training".
        val allZero = SuggestFixtures.input(
            profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":0,"STRENGTH":0,"SOCCER":0}"""),
        )
        assertThat(violations(candidate(SessionType.EASY_RUN, day(4)), allZero)).doesNotContain(ConstraintId.C10)
        assertThat(violations(candidate(SessionType.STRENGTH_UPPER, day(4)), allZero))
            .doesNotContain(ConstraintId.C10)

        // A single sport left at 0 alongside a real cap is still a deliberate cap on that sport.
        val oneZero = SuggestFixtures.input(
            profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":0,"STRENGTH":2}"""),
        )
        assertThat(violations(candidate(SessionType.EASY_RUN, day(4)), oneZero)).contains(ConstraintId.C10)

        assertThat(SportPreferences.capsOf("""{"RUN":0,"STRENGTH":0,"SOCCER":0}""")).isEmpty()
    }

    @Test
    fun c11_minimum_spacing_between_repeated_hard_work() {
        val hardRun = SuggestFixtures.input(
            lockedPlanned = listOf(
                SuggestFixtures.locked(day(0), SessionType.TEMPO_RUN, intensity = Intensity.HIGH),
            ),
        )
        assertThat(violations(candidate(SessionType.INTERVAL_RUN, day(2)), hardRun)).contains(ConstraintId.C11)
        assertThat(violations(candidate(SessionType.INTERVAL_RUN, day(3)), hardRun))
            .doesNotContain(ConstraintId.C11)

        val legs = SuggestFixtures.input(
            lockedPlanned = listOf(
                SuggestFixtures.locked(
                    day(1),
                    SessionType.STRENGTH_LOWER,
                    sportType = SportType.STRENGTH,
                    intensity = Intensity.HIGH,
                ),
            ),
        )
        assertThat(violations(candidate(SessionType.STRENGTH_LOWER, day(3)), legs)).contains(ConstraintId.C11)

        val longRun = SuggestFixtures.input(
            lockedPlanned = listOf(
                SuggestFixtures.locked(day(1), SessionType.LONG_RUN, intensity = Intensity.MODERATE),
            ),
        )
        // Day +5 is a Saturday, so only the 5-day spacing rule can fire here.
        assertThat(violations(candidate(SessionType.LONG_RUN, day(5)), longRun)).contains(ConstraintId.C11)
        assertThat(violations(candidate(SessionType.LONG_RUN, day(6)), longRun)).doesNotContain(ConstraintId.C11)
    }

    @Test
    fun c12_long_run_only_on_allowed_weekday() {
        val input = SuggestFixtures.input()
        // Today is a Monday: +5 = Saturday, +6 = Sunday.
        assertThat(violations(candidate(SessionType.LONG_RUN, day(0)), input)).contains(ConstraintId.C12)
        assertThat(violations(candidate(SessionType.LONG_RUN, day(5)), input)).doesNotContain(ConstraintId.C12)
        assertThat(violations(candidate(SessionType.LONG_RUN, day(6)), input)).doesNotContain(ConstraintId.C12)

        val wednesday = SuggestFixtures.input(
            profile = SuggestFixtures.profile(
                preferredSportsJson = """{"RUN":3,"longRunWeekday":"WEDNESDAY"}""",
            ),
        )
        assertThat(violations(candidate(SessionType.LONG_RUN, day(2)), wednesday))
            .doesNotContain(ConstraintId.C12)
        assertThat(violations(candidate(SessionType.LONG_RUN, day(5)), wednesday)).contains(ConstraintId.C12)
        // The easy sessions are unaffected by C12.
        assertThat(violations(candidate(SessionType.EASY_RUN, day(0)), input)).doesNotContain(ConstraintId.C12)
    }

    // ---- C13: the POLISH-8 repetition guard --------------------------------------------------------

    @Test
    fun c13_no_same_session_type_on_consecutive_days() {
        val input = SuggestFixtures.input(
            lockedPlanned = listOf(SuggestFixtures.locked(day(2), SessionType.EASY_RUN)),
        )

        // The day before and the day after the fixed easy run are both "consecutive".
        assertThat(violations(candidate(SessionType.EASY_RUN, day(1)), input)).contains(ConstraintId.C13)
        assertThat(violations(candidate(SessionType.EASY_RUN, day(3)), input)).contains(ConstraintId.C13)
        // One clear day is enough.
        assertThat(violations(candidate(SessionType.EASY_RUN, day(4)), input)).doesNotContain(ConstraintId.C13)
        assertThat(violations(candidate(SessionType.EASY_RUN, day(0)), input)).doesNotContain(ConstraintId.C13)
        // A different session type on the neighbouring day is fine — C13 is about repetition only.
        assertThat(violations(candidate(SessionType.CROSS_TRAINING, day(3)), input))
            .doesNotContain(ConstraintId.C13)

        // The rule also sees fixed calendar events, which is the POLISH-8 case: a soccer training
        // on the calendar blocks a suggested one the next day.
        val soccer = SuggestFixtures.input(
            events = listOf(SuggestFixtures.event(day(3), EventType.SOCCER_TRAINING)),
        )
        assertThat(violations(candidate(SessionType.SOCCER_TRAINING, day(4)), soccer))
            .contains(ConstraintId.C13)
        assertThat(violations(candidate(SessionType.SOCCER_TRAINING, day(5)), soccer))
            .doesNotContain(ConstraintId.C13)

        // Mobility is exempt: post-pass 7c puts one on every rest day (`sug18`).
        val mobility = SuggestFixtures.input(
            lockedPlanned = listOf(
                SuggestFixtures.locked(day(2), SessionType.MOBILITY, intensity = Intensity.RECOVERY),
            ),
        )
        assertThat(violations(candidate(SessionType.MOBILITY, day(3)), mobility))
            .doesNotContain(ConstraintId.C13)
    }

    @Test
    fun c13b_strength_sessions_48h_apart() {
        val input = SuggestFixtures.input(
            lockedPlanned = listOf(
                SuggestFixtures.locked(
                    day(2),
                    SessionType.STRENGTH_FULL,
                    sportType = SportType.STRENGTH,
                    intensity = Intensity.MODERATE,
                ),
            ),
        )

        // A *different* strength variant on either neighbouring day is still inside 48 h.
        assertThat(violations(candidate(SessionType.STRENGTH_UPPER, day(1)), input))
            .contains(ConstraintId.C13)
        assertThat(violations(candidate(SessionType.STRENGTH_LOWER, day(3)), input))
            .contains(ConstraintId.C13)
        // 48 h clear is allowed again (C11 still spaces STRENGTH_LOWER by 72 h on its own).
        assertThat(violations(candidate(SessionType.STRENGTH_UPPER, day(4)), input))
            .doesNotContain(ConstraintId.C13)
        assertThat(violations(candidate(SessionType.STRENGTH_UPPER, day(0)), input))
            .doesNotContain(ConstraintId.C13)
        // Running next to a strength day is untouched by C13.
        assertThat(violations(candidate(SessionType.EASY_RUN, day(3)), input))
            .doesNotContain(ConstraintId.C13)
    }

    // ---- §3.5.4 catalog ---------------------------------------------------------------------------

    @Test
    fun cat01_est_trimp_matches_trimp_formula() {
        // The §3.5.4 "est. TRIMP" column, un-rounded: `0.30 * rpe * minutes`. The plan's table
        // shows these rounded to whole AU (115.5 -> 115, 229.5 -> 230); the tolerance covers that,
        // and binary floating point (0.30 * 8.5 * 90 = 229.499…) is why it must.
        val expected = mapOf(
            SessionType.RECOVERY_RUN to 27.0,
            SessionType.EASY_RUN to 54.0,
            SessionType.LONG_RUN to 120.0,
            SessionType.TEMPO_RUN to 105.0,
            SessionType.INTERVAL_RUN to 132.0,
            SessionType.STRENGTH_FULL to 99.0,
            SessionType.STRENGTH_UPPER to 81.0,
            SessionType.STRENGTH_LOWER to 115.5,
            SessionType.MOBILITY to 12.0,
            SessionType.CROSS_TRAINING to 72.0,
            SessionType.SOCCER_TRAINING to 175.5,
            SessionType.SOCCER_MATCH to 229.5,
            // P12.3's cycling rows (§3.5.4, appended).
            SessionType.ENDURANCE_RIDE to 108.0,
            SessionType.BIKE_INTERVALS to 144.0,
            SessionType.TRAINER_SESSION to 81.0,
            SessionType.RECOVERY_SPIN to 18.0,
            // P19's benchmark run (not suggestable through the greedy loop).
            SessionType.TIME_TRIAL to 96.0,
        )
        assertThat(SessionCatalog.ALL).hasSize(expected.size)
        SessionCatalog.ALL.forEach { entry ->
            val formula = TrimpDefaults.RPE_TO_TRIMP * entry.rpe * entry.defaultMin
            assertThat(entry.estTrimp).isWithin(1e-9).of(formula)
            assertThat(entry.estTrimp).isWithin(0.01).of(expected.getValue(entry.sessionType))
        }
        // Scaling a session scales its load with it.
        val easy = SessionCatalog.entryFor(SessionType.EASY_RUN)!!
        assertThat(easy.estTrimpFor(90)).isWithin(1e-9).of(easy.estTrimp * 2)
    }

    @Test
    fun a_clean_day_violates_nothing() {
        val input = SuggestFixtures.input()
        assertThat(violations(candidate(SessionType.EASY_RUN, day(2)), input)).isEmpty()
    }

    @Test
    fun sport_preferences_decode_caps_and_long_run_weekday() {
        val raw = """{"RUN":3,"STRENGTH":2,"longRunWeekday":"saturday"}"""
        assertThat(SportPreferences.capsOf(raw)).containsExactly(
            com.myhealth.domain.model.SportGroup.RUN, 3,
            com.myhealth.domain.model.SportGroup.STRENGTH, 2,
        )
        assertThat(SportPreferences.longRunWeekdayOf(raw)).isEqualTo(kotlinx.datetime.DayOfWeek.SATURDAY)
        assertThat(SportPreferences.capsOf("not json")).isEmpty()
        assertThat(SportPreferences.longRunWeekdayOf("{}")).isNull()
    }
}
