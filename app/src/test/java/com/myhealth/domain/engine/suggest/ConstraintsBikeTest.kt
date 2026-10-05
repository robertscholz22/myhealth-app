package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures.candidate
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import org.junit.Test

/**
 * The cycling half of the hard constraints (PLAN §3.5.3 as amended by P12.3), split out of
 * [ConstraintsTest] for rule R10.
 *
 * P12.3 mints exactly one new [ConstraintId] — `C14`, the bike-interval spacing — so the three
 * named tests of the task map onto the rules like this:
 *
 * | Test | Rule it pins |
 * |---|---|
 * | `c14_cycle_cap_respected` | `C10` reading the new `CYCLE` cap of `preferredSportsJson` |
 * | `c15_bike_intervals_spacing_3_days_and_2_from_any_hard` | the new `C14` |
 * | `c16_recovery_spin_allowed_day_after_match` | `C4`, whose recovery-only set gained `RECOVERY_SPIN` |
 */
class ConstraintsBikeTest {

    private fun violations(candidate: Candidate, input: SuggestionInput): List<ConstraintId> =
        Constraints.violations(candidate, candidate.day, SuggestionGrid.seed(input), ConstraintContext.of(input))

    /** A locked ride on [day], so the grid can carry bike work without the engine running. */
    private fun ride(
        day: Long,
        sessionType: SessionType = SessionType.BIKE_INTERVALS,
        intensity: Intensity = Intensity.HIGH,
    ) = SuggestFixtures.locked(
        day = day,
        sessionType = sessionType,
        sportType = SportType.CYCLING,
        intensity = intensity,
        minutes = 60,
        estimatedTrimp = 144.0,
    )

    // ---- C10 with the new CYCLE cap ----------------------------------------------------------------

    @Test
    fun c14_cycle_cap_respected() {
        // Two rides already this week against a cap of 2: a third is C10, exactly as for running.
        val input = SuggestFixtures.input(
            lockedPlanned = listOf(ride(day(0), SessionType.ENDURANCE_RIDE, Intensity.LOW), ride(day(2))),
            profile = SuggestFixtures.profile(
                preferredSportsJson = SuggestFixtures.bikeSportsJson(cycleCap = 2),
            ),
        )
        assertThat(violations(candidate(SessionType.ENDURANCE_RIDE, day(5)), input))
            .contains(ConstraintId.C10)
        // A run is unaffected by the cycling cap…
        assertThat(violations(candidate(SessionType.EASY_RUN, day(5)), input))
            .doesNotContain(ConstraintId.C10)
        // …and one more ride fits under a cap of 3.
        val roomier = SuggestFixtures.input(
            lockedPlanned = listOf(ride(day(0), SessionType.ENDURANCE_RIDE, Intensity.LOW), ride(day(2))),
            profile = SuggestFixtures.profile(
                preferredSportsJson = SuggestFixtures.bikeSportsJson(cycleCap = 3),
            ),
        )
        assertThat(violations(candidate(SessionType.ENDURANCE_RIDE, day(5)), roomier))
            .doesNotContain(ConstraintId.C10)
        // A `CYCLE` cap of 0 alongside non-zero sports is a real cap (POLISH-11 is about all-zero).
        val zero = SuggestFixtures.input(
            profile = SuggestFixtures.profile(
                preferredSportsJson = SuggestFixtures.bikeSportsJson(cycleCap = 0),
            ),
        )
        assertThat(violations(candidate(SessionType.ENDURANCE_RIDE, day(2)), zero))
            .contains(ConstraintId.C10)
    }

    // ---- C14: bike spacing -------------------------------------------------------------------------

    @Test
    fun c15_bike_intervals_spacing_3_days_and_2_from_any_hard() {
        val input = SuggestFixtures.input(lockedPlanned = listOf(ride(day(3))))

        // Two bike interval sessions need 72 h: days +1…+5 are too close, day +6 is fine.
        listOf(1L, 2L, 4L, 5L).forEach { offset ->
            assertThat(violations(candidate(SessionType.BIKE_INTERVALS, day(offset)), input))
                .contains(ConstraintId.C14)
        }
        assertThat(violations(candidate(SessionType.BIKE_INTERVALS, day(6)), input))
            .doesNotContain(ConstraintId.C14)

        // …and 48 h from any other hard work, in both directions.
        listOf(2L, 4L).forEach { offset ->
            assertThat(violations(candidate(SessionType.INTERVAL_RUN, day(offset)), input))
                .contains(ConstraintId.C14)
        }
        assertThat(violations(candidate(SessionType.INTERVAL_RUN, day(5)), input))
            .doesNotContain(ConstraintId.C14)
        // Easy work is never spaced by C14.
        assertThat(violations(candidate(SessionType.ENDURANCE_RIDE, day(4)), input))
            .doesNotContain(ConstraintId.C14)
        assertThat(violations(candidate(SessionType.RECOVERY_SPIN, day(4)), input))
            .doesNotContain(ConstraintId.C14)

        // The symmetric case: a hard run in the grid keeps a bike interval session two days clear.
        val hardRun = SuggestFixtures.input(
            lockedPlanned = listOf(
                SuggestFixtures.locked(day(3), SessionType.TEMPO_RUN, intensity = Intensity.HIGH),
            ),
        )
        assertThat(violations(candidate(SessionType.BIKE_INTERVALS, day(4)), hardRun))
            .contains(ConstraintId.C14)
        assertThat(violations(candidate(SessionType.BIKE_INTERVALS, day(5)), hardRun))
            .doesNotContain(ConstraintId.C14)
        // An empty week spaces nothing.
        assertThat(violations(candidate(SessionType.BIKE_INTERVALS, day(3)), SuggestFixtures.input()))
            .doesNotContain(ConstraintId.C14)
    }

    // ---- C4 with RECOVERY_SPIN --------------------------------------------------------------------

    @Test
    fun c16_recovery_spin_allowed_day_after_match() {
        val input = SuggestFixtures.input(
            events = listOf(SuggestFixtures.event(day(2), EventType.SOCCER_MATCH)),
        )

        assertThat(Constraints.RECOVERY_ONLY_TYPES).contains(SessionType.RECOVERY_SPIN)
        assertThat(violations(candidate(SessionType.RECOVERY_SPIN, day(3)), input))
            .doesNotContain(ConstraintId.C4)
        // The other rides are not recovery: they stay out of the day after a match.
        listOf(SessionType.ENDURANCE_RIDE, SessionType.BIKE_INTERVALS, SessionType.TRAINER_SESSION)
            .forEach { type ->
                assertThat(violations(candidate(type, day(3)), input)).contains(ConstraintId.C4)
            }
    }
}
