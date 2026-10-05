package com.myhealth.domain.engine.load

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.Sex
import org.junit.Test
import kotlinx.datetime.LocalDate

/** HR bounds of PLAN §3.2.1, including the named case `load18`. */
class HrBoundsTest {

    private val today = LocalDate(2026, 1, 1)

    private fun profile(
        restingHrManual: Int? = null,
        maxHrManual: Int? = null,
        birthYear: Int = 1996,
    ) = Profile(
        displayName = "Test",
        sex = Sex.MALE,
        birthDay = LocalDate(birthYear, 1, 1).toEpochDays(),
        heightCm = 180.0,
        restingHrManual = restingHrManual,
        maxHrManual = maxHrManual,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    @Test
    fun load18_tanaka_hrmax_and_observed_override() {
        val tanakaOnly = HrBounds.compute(profile(), today, restingHrLast7Days = listOf(50))
        val observed = HrBounds.compute(
            profile(),
            today,
            restingHrLast7Days = listOf(50),
            observedMaxHrLast365d = 195,
        )

        // age 30 -> 208 - 0.7*30 = 187.
        assertThat(tanakaOnly.hrMax).isEqualTo(187)
        assertThat(observed.hrMax).isEqualTo(195)
    }

    @Test
    fun manual_max_hr_wins_over_tanaka_and_observed() {
        val bounds = HrBounds.compute(
            profile(maxHrManual = 200),
            today,
            restingHrLast7Days = listOf(50),
            observedMaxHrLast365d = 195,
        )

        assertThat(bounds.hrMax).isEqualTo(200)
    }

    @Test
    fun resting_hr_is_the_median_of_the_last_seven_days() {
        val bounds = HrBounds.compute(profile(), today, restingHrLast7Days = listOf(48, 52, 90, 50, 49))

        assertThat(bounds.hrRest).isEqualTo(50)
    }

    @Test
    fun even_number_of_resting_values_averages_the_middle_pair_half_up() {
        val bounds = HrBounds.compute(profile(), today, restingHrLast7Days = listOf(50, 51))

        assertThat(bounds.hrRest).isEqualTo(51)
    }

    @Test
    fun resting_hr_falls_back_to_the_manual_value_then_to_sixty() {
        val manual = HrBounds.compute(profile(restingHrManual = 44), today)
        val neither = HrBounds.compute(profile(), today)

        assertThat(manual.hrRest).isEqualTo(44)
        assertThat(manual.warnings).isEmpty()
        assertThat(neither.hrRest).isEqualTo(60)
        assertThat(neither.warnings.map { it.code }).contains(EngineWarningCode.MISSING_HR)
    }

    @Test
    fun bounds_are_clamped_to_the_safety_window() {
        val lowMax = HrBounds.compute(profile(maxHrManual = 120), today, restingHrLast7Days = listOf(55))
        val highRest = HrBounds.compute(profile(), today, restingHrLast7Days = listOf(120))

        assertThat(lowMax.hrMax).isEqualTo(150)
        assertThat(highRest.hrRest).isEqualTo(90)
        assertThat(lowMax.warnings.map { it.code }).contains(EngineWarningCode.IMPLAUSIBLE_VALUE)
    }

    @Test
    fun reserve_guard_lifts_max_hr_to_at_least_thirty_above_rest() {
        // The two clamps already guarantee a 60 bpm reserve (150 - 90), so the guard is a
        // structural safety net rather than something `compute` can reach — test it directly.
        assertThat(HrBounds.liftedMaxHr(hrMax = 110, hrRest = 90)).isEqualTo(120)
        assertThat(HrBounds.liftedMaxHr(hrMax = 190, hrRest = 50)).isEqualTo(190)

        val worstCase = HrBounds.compute(
            profile(maxHrManual = 150),
            today,
            restingHrLast7Days = listOf(120),
        )
        assertThat(worstCase.hrRest).isEqualTo(90)
        assertThat(worstCase.hrMax).isEqualTo(150)
        assertThat(worstCase.reserve).isAtLeast(30)
    }

    @Test
    fun hrr_is_a_clamped_reserve_fraction() {
        val bounds = HrBounds(hrMax = 190, hrRest = 50)

        assertThat(bounds.hrr(120)).isWithin(1e-9).of(0.5)
        assertThat(bounds.hrr(30)).isEqualTo(0.0)
        assertThat(bounds.hrr(250)).isEqualTo(1.0)
    }
}
