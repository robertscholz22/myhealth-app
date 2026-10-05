package com.myhealth.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MacroTotalsTest {

    private val a = MacroTotals(
        kcal = 500.0, proteinG = 30.0, carbsG = 60.0, fatG = 15.0,
        fiberG = 5.0, sugarG = 10.0, satFatG = 4.0, saltG = 1.0,
    )
    private val b = MacroTotals(
        kcal = 200.0, proteinG = 10.0, carbsG = 20.0, fatG = 5.0,
        fiberG = 2.0, sugarG = 4.0, satFatG = 1.0, saltG = 0.5,
    )

    @Test
    fun plus_sums_every_field() {
        val sum = a + b

        assertThat(sum).isEqualTo(
            MacroTotals(
                kcal = 700.0, proteinG = 40.0, carbsG = 80.0, fatG = 20.0,
                fiberG = 7.0, sugarG = 14.0, satFatG = 5.0, saltG = 1.5,
            ),
        )
    }

    @Test
    fun zero_is_the_additive_identity() {
        assertThat(a + MacroTotals.ZERO).isEqualTo(a)
        assertThat(MacroTotals.ZERO + a).isEqualTo(a)
    }

    @Test
    fun zero_constant_has_every_field_at_zero() {
        assertThat(MacroTotals.ZERO).isEqualTo(
            MacroTotals(
                kcal = 0.0, proteinG = 0.0, carbsG = 0.0, fatG = 0.0,
                fiberG = 0.0, sugarG = 0.0, satFatG = 0.0, saltG = 0.0,
            ),
        )
    }
}
