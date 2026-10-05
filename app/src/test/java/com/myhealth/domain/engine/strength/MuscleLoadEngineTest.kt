package com.myhealth.domain.engine.strength

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.MuscleLoadBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.model.StrengthWorkoutKind
import org.junit.Test

/**
 * `ml01`…`ml12` of PLAN §3.12.4 — the muscle-load engine: the share tables, the per-workout split,
 * the 48-hour half-life and the `0.35 × CTL` bands.
 *
 * Day 0 is an arbitrary epoch day; everything here is relative to it.
 */
class MuscleLoadEngineTest {

    private val today = 20_000L

    private fun compute(ctl: Double = 40.0, vararg sessions: MuscleSession) =
        MuscleLoadEngine.compute(MuscleLoadInput(today = today, ctl = ctl, sessions = sessions.toList()))

    private fun endurance(
        group: SportGroup,
        trimp: Double,
        dayOffset: Long = 0L,
    ) = MuscleSession(day = today + dayOffset, sportGroup = group, trimp = trimp)

    private fun strength(
        trimp: Double,
        sessionType: SessionType? = null,
        workout: StrengthWorkout? = null,
        dayOffset: Long = 0L,
    ) = MuscleSession(
        day = today + dayOffset,
        sportGroup = SportGroup.STRENGTH,
        sessionType = sessionType,
        trimp = trimp,
        workout = workout,
    )

    /** A workout of `exerciseId to sets` rows — the only thing §3.12.4's per-workout split reads. */
    private fun workoutOf(vararg rows: Pair<String, Int>): StrengthWorkout = StrengthWorkout(
        id = 1L,
        name = "Test workout",
        kind = StrengthWorkoutKind.CUSTOM,
        exercises = rows.mapIndexed { index, (exerciseId, sets) ->
            StrengthWorkoutExercise(
                id = index.toLong(),
                workoutId = 1L,
                orderIndex = index,
                exerciseId = exerciseId,
                sets = sets,
                reps = 10,
            )
        },
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    @Test
    fun ml01_run_trimp_100_distribution() {
        val state = compute(sessions = arrayOf(endurance(SportGroup.RUN, 100.0)))

        assertThat(state.loadOf(MuscleGroup.CALVES)).isWithin(TOLERANCE).of(25.0)
        assertThat(state.loadOf(MuscleGroup.QUADS)).isWithin(TOLERANCE).of(22.0)
        assertThat(state.loadOf(MuscleGroup.HAMSTRINGS)).isWithin(TOLERANCE).of(20.0)
        assertThat(state.loadOf(MuscleGroup.GLUTES)).isWithin(TOLERANCE).of(18.0)
        assertThat(state.loadOf(MuscleGroup.CHEST)).isWithin(TOLERANCE).of(0.0)
        // The whole TRIMP is deposited, never more: every row of the table sums to 1.00.
        assertThat(state.byGroup.values.sum()).isWithin(TOLERANCE).of(100.0)
    }

    @Test
    fun ml02_ride_trimp_100_quads_38() {
        val state = compute(sessions = arrayOf(endurance(SportGroup.CYCLE, 100.0)))

        assertThat(state.loadOf(MuscleGroup.QUADS)).isWithin(TOLERANCE).of(38.0)
        assertThat(state.loadOf(MuscleGroup.CALVES)).isWithin(TOLERANCE).of(12.0)
        assertThat(state.loadOf(MuscleGroup.ADDUCTORS)).isWithin(TOLERANCE).of(0.0)
    }

    @Test
    fun ml03_workout_shares_primary_one_secondary_half() {
        // Bench (CHEST primary; TRICEPS + SHOULDERS_FRONT secondary) and barbell row (LATS primary;
        // BICEPS + TRAPS + SHOULDERS_REAR + LOWER_BACK secondary), 3 sets each:
        //   Σw = (3 + 1.5 + 1.5) + (3 + 1.5 + 1.5 + 1.5 + 1.5) = 15.0
        // so CHEST takes 81 × 3/15 = 16.2 and TRICEPS 81 × 1.5/15 = 8.1. The plan's quoted 20.25 /
        // 10.125 assume a two-secondary row (Σw = 12); the catalog's row has four — see the PLAN
        // §3.12.4 note.
        val workout = workoutOf("BARBELL_BENCH_PRESS" to 3, "BARBELL_ROW" to 3)
        val state = compute(sessions = arrayOf(strength(81.0, workout = workout)))

        assertThat(state.loadOf(MuscleGroup.CHEST)).isWithin(TOLERANCE).of(16.2)
        assertThat(state.loadOf(MuscleGroup.TRICEPS)).isWithin(TOLERANCE).of(8.1)
        assertThat(state.loadOf(MuscleGroup.LATS)).isWithin(TOLERANCE).of(16.2)
        // A secondary is worth exactly half a primary.
        assertThat(state.loadOf(MuscleGroup.CHEST))
            .isWithin(TOLERANCE).of(2.0 * state.loadOf(MuscleGroup.TRICEPS))
        assertThat(state.loadOf(MuscleGroup.QUADS)).isWithin(TOLERANCE).of(0.0)
        assertThat(state.byGroup.values.sum()).isWithin(TOLERANCE).of(81.0)
    }

    @Test
    fun ml04_half_life_48h() {
        // CALF_RAISE is CALVES-primary with no secondary, so the whole TRIMP lands on one group.
        val workout = workoutOf("CALF_RAISE" to 3)

        val twoDays = compute(sessions = arrayOf(strength(100.0, workout = workout, dayOffset = -2L)))
        assertThat(twoDays.loadOf(MuscleGroup.CALVES)).isWithin(TOLERANCE).of(50.0)

        val fourDays = compute(sessions = arrayOf(strength(100.0, workout = workout, dayOffset = -4L)))
        assertThat(fourDays.loadOf(MuscleGroup.CALVES)).isWithin(TOLERANCE).of(25.0)

        // …and a session older than the 14-day window contributes nothing at all.
        val ancient = compute(sessions = arrayOf(strength(100.0, workout = workout, dayOffset = -15L)))
        assertThat(ancient.loadOf(MuscleGroup.CALVES)).isWithin(TOLERANCE).of(0.0)
    }

    @Test
    fun ml05_two_runs_sum() {
        val state = compute(
            sessions = arrayOf(
                endurance(SportGroup.RUN, 100.0),
                endurance(SportGroup.RUN, 100.0, dayOffset = -2L),
            ),
        )

        // 25.0 today + 25.0 × 0.5 from two days ago.
        assertThat(state.loadOf(MuscleGroup.CALVES)).isWithin(TOLERANCE).of(37.5)
        assertThat(state.loadOf(MuscleGroup.QUADS)).isWithin(TOLERANCE).of(33.0)
    }

    @Test
    fun ml06_bands_from_ctl_60() {
        val state = compute(ctl = 60.0)

        assertThat(state.ref).isWithin(TOLERANCE).of(21.0)
        assertThat(MuscleLoadEngine.bandFor(15.0, state.ref)).isEqualTo(MuscleLoadBand.FRESH)
        assertThat(MuscleLoadEngine.bandFor(21.0, state.ref)).isEqualTo(MuscleLoadBand.LOADED)
        assertThat(MuscleLoadEngine.bandFor(40.0, state.ref)).isEqualTo(MuscleLoadBand.FATIGUED)
        // The two boundaries themselves: 0.75 × 21 = 15.75 and 1.50 × 21 = 31.5.
        assertThat(MuscleLoadEngine.bandFor(15.75, state.ref)).isEqualTo(MuscleLoadBand.LOADED)
        assertThat(MuscleLoadEngine.bandFor(31.5, state.ref)).isEqualTo(MuscleLoadBand.LOADED)
    }

    @Test
    fun ml07_ref_floor_is_twelve() {
        assertThat(compute(ctl = 10.0).ref).isWithin(TOLERANCE).of(12.0)
        assertThat(compute(ctl = 0.0).ref).isWithin(TOLERANCE).of(12.0)
        // …and above 34.3 CTL the share wins over the floor again.
        assertThat(compute(ctl = 100.0).ref).isWithin(TOLERANCE).of(35.0)
    }

    @Test
    fun ml08_strength_without_workout_uses_session_type_table() {
        val lower = compute(sessions = arrayOf(strength(100.0, sessionType = SessionType.STRENGTH_LOWER)))
        assertThat(lower.loadOf(MuscleGroup.QUADS)).isWithin(TOLERANCE).of(30.0)
        assertThat(lower.loadOf(MuscleGroup.CHEST)).isWithin(TOLERANCE).of(0.0)

        val upper = compute(sessions = arrayOf(strength(100.0, sessionType = SessionType.STRENGTH_UPPER)))
        assertThat(upper.loadOf(MuscleGroup.CHEST)).isWithin(TOLERANCE).of(20.0)
        assertThat(upper.loadOf(MuscleGroup.QUADS)).isWithin(TOLERANCE).of(0.0)

        // An unlinked Garmin strength activity (no session type at all) uses the full-body mean.
        val unknown = compute(sessions = arrayOf(strength(100.0)))
        assertThat(unknown.loadOf(MuscleGroup.QUADS)).isWithin(TOLERANCE).of(15.0)
        assertThat(unknown.loadOf(MuscleGroup.CHEST)).isWithin(TOLERANCE).of(10.0)
        assertThat(unknown.byGroup.values.sum()).isWithin(TOLERANCE).of(100.0)
    }

    @Test
    fun ml09_soccer_loads_adductors() {
        val state = compute(sessions = arrayOf(endurance(SportGroup.SOCCER, 100.0)))

        assertThat(state.loadOf(MuscleGroup.ADDUCTORS)).isWithin(TOLERANCE).of(12.0)
        // …more than running, which is the whole point of a separate row.
        val run = compute(sessions = arrayOf(endurance(SportGroup.RUN, 100.0)))
        assertThat(state.loadOf(MuscleGroup.ADDUCTORS)).isGreaterThan(run.loadOf(MuscleGroup.ADDUCTORS))
    }

    @Test
    fun ml10_lower_body_is_the_max_of_five_groups() {
        val state = compute(ctl = 60.0, sessions = arrayOf(endurance(SportGroup.RUN, 100.0)))

        val lowerGroups = MuscleGroup.entries.filter { it.isLowerBody }
        assertThat(lowerGroups).hasSize(5)
        assertThat(state.lowerBodyLoad)
            .isWithin(TOLERANCE)
            .of(lowerGroups.maxOf { state.loadOf(it) })
        assertThat(state.lowerBodyLoad).isWithin(TOLERANCE).of(25.0)
        // 25.0 AU against ref 21.0: above 0.75 × ref, below 1.50 × ref.
        assertThat(state.lowerBody).isEqualTo(MuscleLoadBand.LOADED)

        // The upper half is the max over the other eleven — here the 5 AU the trunk took.
        assertThat(state.upperBodyLoad).isWithin(TOLERANCE).of(5.0)
        assertThat(state.upperBody).isEqualTo(MuscleLoadBand.FRESH)
    }

    @Test
    fun ml11_empty_input_all_fresh() {
        val state = compute(ctl = 60.0)

        assertThat(state.byGroup).hasSize(MuscleGroup.entries.size)
        assertThat(state.byGroup.values.all { it == 0.0 }).isTrue()
        assertThat(state.bands.values.toSet()).containsExactly(MuscleLoadBand.FRESH)
        assertThat(state.lowerBody).isEqualTo(MuscleLoadBand.FRESH)
        assertThat(state.upperBody).isEqualTo(MuscleLoadBand.FRESH)
        assertThat(state.intensityOf(MuscleGroup.QUADS)).isWithin(TOLERANCE).of(0.0)
    }

    @Test
    fun ml12_unknown_sport_contributes_nothing() {
        val state = compute(sessions = arrayOf(endurance(SportGroup.OTHER, 200.0)))

        assertThat(state.byGroup.values.sum()).isWithin(TOLERANCE).of(0.0)
        assertThat(state.lowerBody).isEqualTo(MuscleLoadBand.FRESH)
    }

    private companion object {
        const val TOLERANCE: Double = 1e-6
    }
}
