package com.myhealth.ui.common

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.After
import org.junit.Test

/**
 * [fmtDecimal], [fmtInt] and [parseDecimal] (POLISH-12): decimals and grouping follow
 * [Locale.getDefault], and parsing accepts either decimal separator regardless of locale. Restores
 * the JVM-wide default locale in [tearDown] so this test cannot leak state into others.
 */
class NumbersTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun fmtDecimal_under_germany_uses_a_comma() {
        Locale.setDefault(Locale.GERMANY)
        assertThat(fmtDecimal(8.8, 1)).isEqualTo("8,8")
    }

    @Test
    fun fmtDecimal_under_us_uses_a_dot() {
        Locale.setDefault(Locale.US)
        assertThat(fmtDecimal(8.8, 1)).isEqualTo("8.8")
    }

    @Test
    fun fmtInt_under_germany_groups_with_a_dot() {
        Locale.setDefault(Locale.GERMANY)
        assertThat(fmtInt(1234)).isEqualTo("1.234")
    }

    @Test
    fun fmtInt_under_us_groups_with_a_comma() {
        Locale.setDefault(Locale.US)
        assertThat(fmtInt(1234)).isEqualTo("1,234")
    }

    @Test
    fun fmtKg_appends_unit() {
        Locale.setDefault(Locale.US)
        assertThat(fmtKg(77.0)).isEqualTo("77.0 kg")
    }

    @Test
    fun fmtKm_appends_unit() {
        Locale.setDefault(Locale.US)
        assertThat(fmtKm(7.2)).isEqualTo("7.20 km")
    }

    @Test
    fun fmtPercent_appends_sign() {
        Locale.setDefault(Locale.US)
        assertThat(fmtPercent(58.7)).isEqualTo("58.7 %")
    }

    @Test
    fun parseDecimal_accepts_comma_and_dot_identically() {
        assertThat(parseDecimal("58,7")).isEqualTo(parseDecimal("58.7"))
        assertThat(parseDecimal("58.7")).isEqualTo(58.7)
    }

    @Test
    fun parseDecimal_rejects_garbage() {
        assertThat(parseDecimal("garbage")).isNull()
        assertThat(parseDecimal("")).isNull()
        assertThat(parseDecimal("-")).isNull()
    }
}
