package com.myhealth.ui.onboarding

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.Sex
import com.myhealth.domain.util.plusDays
import org.junit.Test
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.DateTimeUnit

/**
 * Unit tests for the pure `validate(draft)` function (PLAN P1.10). [TODAY] is fixed so birth-date
 * rules ("≥ 10 years ago", "not in the future") are deterministic.
 */
class OnboardingValidationTest {

    private val TODAY = LocalDate(2026, 9, 12)

    private val validDraft = OnboardingDraft(
        displayName = "Robert",
        sex = Sex.MALE,
        birthDay = LocalDate(1990, 1, 1),
        heightCm = 180.0,
        weightKg = 78.0,
        goalWeightKg = 75.0,
        goalPaceKgPerWeek = -0.3,
    )

    @Test
    fun valid_draft_has_no_errors() {
        val errors = validate(validDraft, TODAY)

        assertThat(errors).isEmpty()
    }

    @Test
    fun blank_name_is_invalid() {
        val errors = validate(validDraft.copy(displayName = "   "), TODAY)

        assertThat(errors).containsKey(OnboardingField.NAME)
    }

    @Test
    fun missing_birth_date_is_invalid() {
        val errors = validate(validDraft.copy(birthDay = null), TODAY)

        assertThat(errors).containsKey(OnboardingField.BIRTH_DATE)
    }

    @Test
    fun birth_date_in_the_future_is_invalid() {
        val errors = validate(validDraft.copy(birthDay = TODAY.plusDays(1)), TODAY)

        assertThat(errors).containsKey(OnboardingField.BIRTH_DATE)
    }

    @Test
    fun birth_date_less_than_ten_years_ago_is_invalid() {
        val errors = validate(validDraft.copy(birthDay = TODAY.minus(5, DateTimeUnit.YEAR)), TODAY)

        assertThat(errors).containsKey(OnboardingField.BIRTH_DATE)
    }

    @Test
    fun birth_date_exactly_ten_years_ago_is_valid() {
        val errors = validate(validDraft.copy(birthDay = TODAY.minus(10, DateTimeUnit.YEAR)), TODAY)

        assertThat(errors).doesNotContainKey(OnboardingField.BIRTH_DATE)
    }

    @Test
    fun height_below_minimum_is_invalid() {
        val errors = validate(validDraft.copy(heightCm = 99.9), TODAY)

        assertThat(errors).containsKey(OnboardingField.HEIGHT)
    }

    @Test
    fun height_above_maximum_is_invalid() {
        val errors = validate(validDraft.copy(heightCm = 250.1), TODAY)

        assertThat(errors).containsKey(OnboardingField.HEIGHT)
    }

    @Test
    fun missing_height_is_invalid() {
        val errors = validate(validDraft.copy(heightCm = null), TODAY)

        assertThat(errors).containsKey(OnboardingField.HEIGHT)
    }

    @Test
    fun weight_below_minimum_is_invalid() {
        val errors = validate(validDraft.copy(weightKg = 29.9), TODAY)

        assertThat(errors).containsKey(OnboardingField.WEIGHT)
    }

    @Test
    fun weight_above_maximum_is_invalid() {
        val errors = validate(validDraft.copy(weightKg = 250.1), TODAY)

        assertThat(errors).containsKey(OnboardingField.WEIGHT)
    }

    @Test
    fun goal_weight_out_of_range_is_invalid() {
        val errors = validate(validDraft.copy(goalWeightKg = 251.0), TODAY)

        assertThat(errors).containsKey(OnboardingField.GOAL_WEIGHT)
    }

    @Test
    fun null_goal_weight_is_valid_since_it_is_optional() {
        val errors = validate(validDraft.copy(goalWeightKg = null), TODAY)

        assertThat(errors).doesNotContainKey(OnboardingField.GOAL_WEIGHT)
    }

    @Test
    fun goal_pace_below_minimum_is_invalid() {
        val errors = validate(validDraft.copy(goalPaceKgPerWeek = -1.1), TODAY)

        assertThat(errors).containsKey(OnboardingField.GOAL_PACE)
    }

    @Test
    fun goal_pace_above_maximum_is_invalid() {
        val errors = validate(validDraft.copy(goalPaceKgPerWeek = 0.6), TODAY)

        assertThat(errors).containsKey(OnboardingField.GOAL_PACE)
    }
}
