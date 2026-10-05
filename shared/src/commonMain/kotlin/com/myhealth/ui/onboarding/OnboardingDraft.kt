package com.myhealth.ui.onboarding

import com.myhealth.data.time.systemClock
import com.myhealth.data.time.today
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SportGroup
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * POLISH-11: a skipped "sessions / week" step must not leave every sport capped at 0 (which starves
 * the suggester down to cross-training/mobility only, see [com.myhealth.domain.engine.suggest.Constraints]
 * C10). Run 2 / Strength 2 / Soccer 1 is a gentle, generally-safe starting point; the user can still
 * set any value (including 0) explicitly in step 3.
 *
 * P12.3 adds Cycle at **0**: the cycling cap is the gate for the bike sessions of §3.5.8, so a new
 * athlete gets rides only after asking for them (or after setting a `BIKE_*` goal). A single zero
 * next to the non-zero rows is a real cap, not POLISH-11's "no preferences configured".
 */
val DEFAULT_SESSIONS_PER_WEEK: Map<SportGroup, Int> = mapOf(
    SportGroup.RUN to 2,
    SportGroup.STRENGTH to 2,
    SportGroup.SOCCER to 1,
    SportGroup.CYCLE to 0,
)

/** The 3-step onboarding form state (§4.2 Onboarding), before it becomes a [com.myhealth.domain.model.Profile]. */
data class OnboardingDraft(
    val displayName: String = "",
    val sex: Sex = Sex.MALE,
    val birthDay: LocalDate? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val goalWeightKg: Double? = null,
    val goalPaceKgPerWeek: Double? = 0.0,
    val neatLevel: NeatLevel = NeatLevel.LIGHT_ACTIVE,
    val sessionsPerWeek: Map<SportGroup, Int> = DEFAULT_SESSIONS_PER_WEEK,
    val mobilityOnRestDays: Boolean = true,
    val sleepTargetHours: Double? = 8.0,
    /** P11.3: the switch shown in step 3 when [sex] is `FEMALE` — on by default (PLAN §5 P11). */
    val cycleTrackingEnabled: Boolean = true,
)

/** Field identity for validation errors and per-step gating. */
enum class OnboardingField { NAME, BIRTH_DATE, HEIGHT, WEIGHT, GOAL_WEIGHT, GOAL_PACE }

enum class OnboardingStep(val fields: Set<OnboardingField>) {
    IDENTITY(setOf(OnboardingField.NAME, OnboardingField.BIRTH_DATE)),
    BODY(setOf(OnboardingField.HEIGHT, OnboardingField.WEIGHT, OnboardingField.GOAL_WEIGHT, OnboardingField.GOAL_PACE)),
    PREFERENCES(emptySet()),
}

private const val MIN_HEIGHT_CM = 100.0
private const val MAX_HEIGHT_CM = 250.0
private const val MIN_WEIGHT_KG = 30.0
private const val MAX_WEIGHT_KG = 250.0
private const val MIN_GOAL_PACE_KG_PER_WEEK = -1.0
private const val MAX_GOAL_PACE_KG_PER_WEEK = 0.5
private const val MIN_ONBOARDING_AGE_YEARS = 10L

/**
 * Pure validation (unit-tested in `OnboardingValidationTest`) — no Android/Compose dependency.
 * [today] defaults to the real "now" but is overridable so birth-date rules are deterministic
 * in tests (§1.3: engines/pure functions take the clock/date as a parameter).
 */
fun validate(draft: OnboardingDraft, today: LocalDate = systemClock().today()): Map<OnboardingField, UiMessage> {
    val errors = mutableMapOf<OnboardingField, UiMessage>()

    if (draft.displayName.isBlank()) {
        errors[OnboardingField.NAME] = UiMessage.of(Res.string.onboarding_name_required)
    }

    val birthDay = draft.birthDay
    val earliestAllowedBirthDay = today.minus(MIN_ONBOARDING_AGE_YEARS, DateTimeUnit.YEAR)
    when {
        birthDay == null -> errors[OnboardingField.BIRTH_DATE] = UiMessage.of(Res.string.onboarding_birth_date_required)
        birthDay > today -> errors[OnboardingField.BIRTH_DATE] = UiMessage.of(Res.string.onboarding_birth_date_future)
        birthDay > earliestAllowedBirthDay ->
            errors[OnboardingField.BIRTH_DATE] =
                UiMessage.of(Res.string.onboarding_birth_date_min_age, MIN_ONBOARDING_AGE_YEARS)
    }

    val height = draft.heightCm
    if (height == null || height < MIN_HEIGHT_CM || height > MAX_HEIGHT_CM) {
        errors[OnboardingField.HEIGHT] =
            UiMessage.of(Res.string.onboarding_height_range, MIN_HEIGHT_CM.toInt(), MAX_HEIGHT_CM.toInt())
    }

    val weight = draft.weightKg
    if (weight == null || weight < MIN_WEIGHT_KG || weight > MAX_WEIGHT_KG) {
        errors[OnboardingField.WEIGHT] =
            UiMessage.of(Res.string.onboarding_weight_range, MIN_WEIGHT_KG.toInt(), MAX_WEIGHT_KG.toInt())
    }

    val goalWeight = draft.goalWeightKg
    if (goalWeight != null && (goalWeight < MIN_WEIGHT_KG || goalWeight > MAX_WEIGHT_KG)) {
        errors[OnboardingField.GOAL_WEIGHT] =
            UiMessage.of(Res.string.onboarding_goal_weight_range, MIN_WEIGHT_KG.toInt(), MAX_WEIGHT_KG.toInt())
    }

    val pace = draft.goalPaceKgPerWeek
    if (pace == null || pace < MIN_GOAL_PACE_KG_PER_WEEK || pace > MAX_GOAL_PACE_KG_PER_WEEK) {
        errors[OnboardingField.GOAL_PACE] =
            UiMessage.of(Res.string.onboarding_goal_pace_range, MIN_GOAL_PACE_KG_PER_WEEK, MAX_GOAL_PACE_KG_PER_WEEK)
    }

    return errors
}
