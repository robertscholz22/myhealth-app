package com.myhealth.domain.engine.label

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Step 2 of PLAN §3.6.1: the number regex, comma decimals, the kJ thousands guard, upper
 * bounds, unit suffixes and the > 4 integer digit rejection. */
class NumberTokenizerTest {

    @Test
    fun tok01_comma_decimal_becomes_a_dot() {
        val tokens = NumberTokenizer.tokenize("eiweiss 13,5 g")

        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].value).isEqualTo(13.5)
        assertThat(tokens[0].unit).isEqualTo(NumberUnit.G)
        assertThat(tokens[0].isUpperBound).isFalse()
    }

    @Test
    fun tok02_thousands_separator_is_only_read_for_kj() {
        val kilojoules = NumberTokenizer.tokenize("brennwert 1.560 kj")
        val grams = NumberTokenizer.tokenize("kohlenhydrate 1.560 g")

        assertThat(kilojoules.single().value).isEqualTo(1560.0)
        assertThat(kilojoules.single().unit).isEqualTo(NumberUnit.KJ)
        assertThat(grams.single().value).isEqualTo(1.56)
    }

    @Test
    fun tok03_upper_bound_prefix_is_kept() {
        val less = NumberTokenizer.tokenize("davon zucker < 0,5 g").single()
        val atMost = NumberTokenizer.tokenize("salz ≤ 0,1 g").single()

        assertThat(less.value).isEqualTo(0.5)
        assertThat(less.isUpperBound).isTrue()
        assertThat(atMost.value).isEqualTo(0.1)
        assertThat(atMost.isUpperBound).isTrue()
    }

    @Test
    fun tok04_unit_suffixes_are_recognised() {
        val units = NumberTokenizer.tokenize("1548 kJ 370 kcal 160 mg 100 ml 7 g").map { it.unit }

        assertThat(units).containsExactly(
            NumberUnit.KJ,
            NumberUnit.KCAL,
            NumberUnit.MG,
            NumberUnit.ML,
            NumberUnit.G,
        ).inOrder()
    }

    @Test
    fun tok05_numbers_with_more_than_four_integer_digits_are_rejected() {
        assertThat(NumberTokenizer.tokenize("energie 12345 kcal")).isEmpty()
        assertThat(NumberTokenizer.tokenize("energie 1234 kcal").single().value).isEqualTo(1234.0)
    }

    @Test
    fun tok06_micrograms_are_tokenised_in_both_spellings() {
        val micro = NumberTokenizer.tokenize("vitamin d 45 µg").single()
        val greekMu = NumberTokenizer.tokenize(Lexicon.normalise("Vitamin D 45 µg")).single()
        val ascii = NumberTokenizer.tokenize("vitamin d 45 ug").single()

        assertThat(micro.unit).isEqualTo(NumberUnit.UG)
        assertThat(greekMu.unit).isEqualTo(NumberUnit.UG)
        assertThat(ascii.unit).isEqualTo(NumberUnit.UG)
        assertThat(micro.value).isEqualTo(45.0)
    }

    @Test
    fun tok07_character_offsets_locate_the_numeral() {
        val text = "fett 0,2 g 0,3 g"
        val tokens = NumberTokenizer.tokenize(text)

        assertThat(tokens).hasSize(2)
        assertThat(text.substring(tokens[0].start, tokens[0].end)).isEqualTo("0,2")
        assertThat(text.substring(tokens[1].start, tokens[1].end)).isEqualTo("0,3")
        assertThat(tokens[0].centerOffset).isLessThan(tokens[1].centerOffset)
    }

    @Test
    fun tok08_parenthesised_serving_grams_are_extracted() {
        assertThat(NumberTokenizer.parenthesised("je portion (150 g)")).isEqualTo("150 g")
        assertThat(NumberTokenizer.gramsIn("150 g")).isEqualTo(150.0)
        assertThat(NumberTokenizer.gramsIn("serving size 1 bar")).isNull()
    }
}
