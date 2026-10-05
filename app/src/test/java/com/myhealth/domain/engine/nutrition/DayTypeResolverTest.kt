package com.myhealth.domain.engine.nutrition

import com.myhealth.domain.util.minusDays
import com.myhealth.domain.util.plusDays
import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.nutrition.NutritionFixtures.TODAY
import com.myhealth.domain.engine.nutrition.NutritionFixtures.activity
import com.myhealth.domain.engine.nutrition.NutritionFixtures.occurrence
import com.myhealth.domain.engine.nutrition.NutritionFixtures.planned
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import org.junit.Test

/**
 * All eight rows of the resolution table of PLAN §3.1.6, in the order the table gives them, plus
 * the precedence between rows that can match at the same time.
 */
class DayTypeResolverTest {

    private val tomorrow = TODAY.plusDays(1)
    private val yesterday = TODAY.minusDays(1)

    private fun resolve(
        events: List<EventOccurrence> = emptyList(),
        planned: List<PlannedSession> = emptyList(),
        completed: List<ActivitySummary> = emptyList(),
    ): DayType = DayTypeResolver.resolve(
        DayTypeResolver.Input(
            date = TODAY,
            events = events,
            plannedSessions = planned,
            completedSessions = completed,
        ),
    )

    @Test
    fun row1_a_race_today_is_a_race_day() {
        assertThat(resolve(events = listOf(occurrence(TODAY, EventType.RACE)))).isEqualTo(DayType.RACE_DAY)
    }

    @Test
    fun row2_a_match_today_is_a_match_day() {
        assertThat(resolve(events = listOf(occurrence(TODAY, EventType.SOCCER_MATCH))))
            .isEqualTo(DayType.MATCH_DAY)
    }

    @Test
    fun row3_a_long_race_tomorrow_is_a_pre_race_day() {
        val long = occurrence(tomorrow, EventType.RACE, targetDistanceMeters = 21_097.5)
        val short = occurrence(tomorrow, EventType.RACE, targetDistanceMeters = 5_000.0)

        assertThat(resolve(events = listOf(long))).isEqualTo(DayType.PRE_RACE)
        // Below 10 km there is nothing to carb-load for: the row does not match.
        assertThat(resolve(events = listOf(short))).isEqualTo(DayType.REST)
    }

    @Test
    fun row4_a_match_tomorrow_is_a_pre_match_day() {
        assertThat(resolve(events = listOf(occurrence(tomorrow, EventType.SOCCER_MATCH))))
            .isEqualTo(DayType.PRE_MATCH)
    }

    @Test
    fun row5_the_day_after_a_match_and_a_recovery_only_plan_are_recovery_days() {
        assertThat(resolve(events = listOf(occurrence(yesterday, EventType.SOCCER_MATCH))))
            .isEqualTo(DayType.RECOVERY)
        assertThat(resolve(events = listOf(occurrence(yesterday, EventType.RACE))))
            .isEqualTo(DayType.RECOVERY)
        assertThat(
            resolve(
                planned = listOf(
                    planned(sessionType = SessionType.RECOVERY_RUN, targetDurationMin = 30),
                    planned(id = 2L, sessionType = SessionType.MOBILITY, targetDurationMin = 20),
                ),
            ),
        ).isEqualTo(DayType.RECOVERY)
    }

    @Test
    fun row6_load_duration_or_session_type_makes_the_day_hard() {
        // Estimated TRIMP ≥ 120.
        assertThat(resolve(planned = listOf(planned(estimatedTrimp = 130.0, targetDurationMin = 45))))
            .isEqualTo(DayType.HARD_TRAINING)
        // Total planned duration ≥ 100 min.
        assertThat(resolve(planned = listOf(planned(targetDurationMin = 100))))
            .isEqualTo(DayType.HARD_TRAINING)
        // A quality session, whatever its duration.
        assertThat(
            resolve(planned = listOf(planned(sessionType = SessionType.INTERVAL_RUN, targetDurationMin = 40))),
        ).isEqualTo(DayType.HARD_TRAINING)
        assertThat(
            resolve(planned = listOf(planned(sessionType = SessionType.STRENGTH_LOWER, targetDurationMin = 40))),
        ).isEqualTo(DayType.HARD_TRAINING)
        // Completed soccer training counts too.
        assertThat(
            resolve(completed = listOf(activity(sportType = SportType.SOCCER_TRAINING, durationMin = 60))),
        ).isEqualTo(DayType.HARD_TRAINING)
    }

    @Test
    fun row7_any_other_session_makes_it_a_training_day() {
        assertThat(resolve(planned = listOf(planned(targetDurationMin = 45))))
            .isEqualTo(DayType.TRAINING)
        assertThat(resolve(completed = listOf(activity(durationMin = 45))))
            .isEqualTo(DayType.TRAINING)
    }

    @Test
    fun row8_an_empty_day_is_a_rest_day() {
        assertThat(resolve()).isEqualTo(DayType.REST)
    }

    @Test
    fun the_first_matching_row_wins() {
        // Row 1 beats row 2, and both beat rows 3–4.
        val everything = listOf(
            occurrence(TODAY, EventType.RACE, eventId = 1L, targetDistanceMeters = 10_000.0),
            occurrence(TODAY, EventType.SOCCER_MATCH, eventId = 2L),
            occurrence(tomorrow, EventType.RACE, eventId = 3L, targetDistanceMeters = 42_195.0),
            occurrence(tomorrow, EventType.SOCCER_MATCH, eventId = 4L),
        )
        assertThat(resolve(events = everything)).isEqualTo(DayType.RACE_DAY)
        assertThat(resolve(events = everything.drop(1))).isEqualTo(DayType.MATCH_DAY)
        assertThat(resolve(events = everything.drop(2))).isEqualTo(DayType.PRE_RACE)
        assertThat(resolve(events = everything.drop(3))).isEqualTo(DayType.PRE_MATCH)

        // Row 5 beats rows 6–7: a recovery-only plan is never "hard" and never plain "training".
        assertThat(
            resolve(
                events = listOf(occurrence(yesterday, EventType.SOCCER_MATCH)),
                planned = listOf(planned(sessionType = SessionType.INTERVAL_RUN, targetDurationMin = 120)),
            ),
        ).isEqualTo(DayType.RECOVERY)
    }
}
