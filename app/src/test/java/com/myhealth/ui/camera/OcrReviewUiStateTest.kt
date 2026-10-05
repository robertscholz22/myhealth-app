package com.myhealth.ui.camera

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NutritionFacts
import com.myhealth.domain.model.NutritionFactsDraft
import com.myhealth.domain.model.ParsedValue
import com.myhealth.ui.common.ConfidenceLevel
import com.myhealth.ui.common.confidenceLevelOf
import org.junit.Test

/**
 * The pure parts of the OCR review screen (PLAN P4.9): the confidence colour buckets, the
 * per-serving column swap, and what "Accept" hands to the ingredient editor.
 */
class OcrReviewUiStateTest {

    @Test
    fun review01_confidence_buckets_use_the_0_9_and_0_7_thresholds() {
        assertThat(confidenceLevelOf(1.0)).isEqualTo(ConfidenceLevel.HIGH)
        assertThat(confidenceLevelOf(0.9)).isEqualTo(ConfidenceLevel.HIGH)
        assertThat(confidenceLevelOf(0.8999)).isEqualTo(ConfidenceLevel.MEDIUM)
        assertThat(confidenceLevelOf(0.7)).isEqualTo(ConfidenceLevel.MEDIUM)
        assertThat(confidenceLevelOf(0.6999)).isEqualTo(ConfidenceLevel.LOW)
        assertThat(confidenceLevelOf(0.0)).isEqualTo(ConfidenceLevel.LOW)

        val state = reviewStateOf(ScanDraft(facts = DRAFT))
        assertThat(state.valueOf(OcrField.KCAL).level).isEqualTo(ConfidenceLevel.HIGH)
        assertThat(state.valueOf(OcrField.SUGAR).level).isEqualTo(ConfidenceLevel.MEDIUM)
        assertThat(state.valueOf(OcrField.FAT).level).isEqualTo(ConfidenceLevel.LOW)
    }

    @Test
    fun review02_per_serving_toggle_swaps_the_values_and_the_basis_note() {
        val per100 = reviewStateOf(ScanDraft(facts = DRAFT))
        assertThat(per100.hasPerServing).isTrue()
        assertThat(per100.basisNote).isEqualTo("Values are per 100 g")
        assertThat(per100.valueOf(OcrField.KCAL).value).isEqualTo(412.0)
        assertThat(per100.valueOf(OcrField.FAT).value).isEqualTo(17.4)

        val perServing = per100.withPerServing(true)
        assertThat(perServing.basisNote).isEqualTo("Values are per serving (1 bar (30 g))")
        assertThat(perServing.valueOf(OcrField.KCAL).value).isEqualTo(124.0)
        assertThat(perServing.valueOf(OcrField.FAT).value).isEqualTo(5.2)
        // The swapped rows keep the confidence of the column they were read beside.
        assertThat(perServing.valueOf(OcrField.FAT).confidence).isEqualTo(0.55)

        // And back again, unchanged.
        assertThat(perServing.withPerServing(false).values).isEqualTo(per100.values)

        // A draft without a second column ignores the toggle entirely.
        val single = reviewStateOf(ScanDraft(facts = DRAFT.copy(perServing = null)))
        assertThat(single.hasPerServing).isFalse()
        assertThat(single.withPerServing(true).usePerServing).isFalse()
    }

    @Test
    fun review03_the_first_red_field_in_form_order_takes_the_focus() {
        val state = reviewStateOf(ScanDraft(facts = DRAFT))

        // Form order is kcal, fat, saturates, … — fat (0.55) is the first red one, even though
        // saturates (0.45) is less certain still.
        assertThat(state.firstLowConfidence).isEqualTo(OcrField.FAT)

        val fixed = state.withValue(OcrField.FAT, 17.4)
        assertThat(fixed.valueOf(OcrField.FAT).confidence).isEqualTo(1.0)
        assertThat(fixed.firstLowConfidence).isEqualTo(OcrField.SAT_FAT)

        // A field the parser never filled is not "red" — there is nothing to check.
        val empty = reviewStateOf(ScanDraft(facts = NutritionFactsDraft(basis = MeasureBasis.PER_100G)))
        assertThat(empty.firstLowConfidence).isNull()
    }

    @Test
    fun review04_accept_carries_the_edited_values_and_the_per_serving_basis() {
        val edited = reviewStateOf(ScanDraft(facts = DRAFT))
            .withValue(OcrField.FAT, 18.0)
            .withValue(OcrField.SALT, 0.6)

        val facts = edited.acceptedFacts()
        assertThat(facts.basis).isEqualTo(MeasureBasis.PER_100G)
        assertThat(facts.fatG.value).isEqualTo(18.0)
        assertThat(facts.fatG.confidence).isEqualTo(1.0)
        assertThat(facts.energyKcal.value).isEqualTo(412.0)
        assertThat(facts.saltG.value).isEqualTo(0.6)
        // Sodium is always derived from salt, never carried over (§3.6.1 step 5).
        assertThat(facts.sodiumG.value).isWithin(1e-9).of(0.24)
        assertThat(facts.perServing).isNull()

        val servingFacts = edited.withPerServing(true).acceptedFacts()
        assertThat(servingFacts.basis).isEqualTo(MeasureBasis.PER_PIECE)
        assertThat(servingFacts.energyKcal.value).isEqualTo(124.0)
        assertThat(servingFacts.servingGrams).isEqualTo(30.0)
    }

    private companion object {
        val DRAFT = NutritionFactsDraft(
            basis = MeasureBasis.PER_100G,
            energyKcal = ParsedValue(412.0, 0.95, 3),
            energyKj = ParsedValue(1724.0, 0.9, 3),
            fatG = ParsedValue(17.4, 0.55, 4),
            satFatG = ParsedValue(8.1, 0.45, 5),
            carbsG = ParsedValue(53.0, 0.88, 6),
            sugarG = ParsedValue(21.0, 0.72, 7),
            fiberG = ParsedValue(4.2, 0.93, 8),
            proteinG = ParsedValue(7.8, 0.93, 9),
            saltG = ParsedValue(0.55, 0.91, 10),
            servingGrams = 30.0,
            servingLabel = "1 bar (30 g)",
            perServing = NutritionFacts(
                basis = MeasureBasis.PER_PIECE,
                kcal = 124.0,
                proteinG = 2.3,
                carbsG = 15.9,
                sugarG = 6.3,
                fatG = 5.2,
                satFatG = 2.4,
                fiberG = 1.3,
                saltG = 0.17,
                sodiumG = 0.07,
            ),
        )
    }
}
