package com.myhealth.data.off

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.MeasureBasis
import org.junit.Test

/**
 * [OffMapper] over saved Open Food Facts v2 bodies (PLAN P4.10). Nothing here touches the network:
 * every case decodes a fixture from `app/src/test/resources/fixtures/off/` (or a literal body for
 * the shapes OFF only produces occasionally) with the client's own [OFF_JSON] configuration.
 */
class OffMapperTest {

    @Test
    fun off01_full_product_maps_all_ten_nutriment_keys() {
        val facts = product("full_product")!!.facts

        assertThat(facts.energyKcal.value).isEqualTo(539.0)
        assertThat(facts.energyKj.value).isEqualTo(2252.0)
        assertThat(facts.fatG.value).isEqualTo(30.9)
        assertThat(facts.satFatG.value).isEqualTo(10.6)
        assertThat(facts.carbsG.value).isEqualTo(57.5)
        assertThat(facts.sugarG.value).isEqualTo(56.3)
        assertThat(facts.fiberG.value).isEqualTo(0.0)
        assertThat(facts.proteinG.value).isEqualTo(6.3)
        assertThat(facts.saltG.value).isEqualTo(0.107)
        assertThat(facts.sodiumG.value).isEqualTo(0.0428)
        // Values OFF states outright are trusted completely; the review form shows them green.
        assertThat(facts.energyKcal.confidence).isEqualTo(1.0)
    }

    @Test
    fun off02_full_product_carries_identity_basis_and_serving_grams() {
        val product = product("full_product")!!

        assertThat(product.barcode).isEqualTo("3017620422003")
        assertThat(product.name).isEqualTo("Nutella")
        // Only the first of OFF's comma-separated brands ("Ferrero, Nutella").
        assertThat(product.brand).isEqualTo("Ferrero")
        assertThat(product.imageUrl).isNotNull()
        assertThat(product.facts.basis).isEqualTo(MeasureBasis.PER_100G)
        assertThat(product.facts.servingGrams).isEqualTo(15.0)
        assertThat(product.facts.servingLabel).isEqualTo("15 g")
    }

    @Test
    fun off03_sparse_product_has_no_nutrients_and_does_not_fail() {
        val product = product("sparse_product")!!

        assertThat(product.name).isEqualTo("Mineralwasser still")
        assertThat(product.brand).isNull()
        assertThat(product.facts.energyKcal.value).isNull()
        assertThat(product.facts.proteinG.value).isNull()
        assertThat(product.facts.saltG.value).isNull()
        assertThat(product.facts.sodiumG.value).isNull()
        assertThat(product.facts.energyKcal.confidence).isEqualTo(0.0)
    }

    @Test
    fun off04_millilitre_quantity_switches_the_basis_and_the_serving() {
        val facts = product("sparse_product")!!.facts

        assertThat(facts.basis).isEqualTo(MeasureBasis.PER_100ML)
        assertThat(facts.servingGrams).isEqualTo(250.0)
    }

    @Test
    fun off05_kj_only_product_derives_kcal_half_up() {
        val facts = product(
            body = """
                {"status":1,"code":"1111111111111","product":{"product_name":"Kj only",
                "quantity":"250 g","nutriments":{"energy-kj_100g":1000,"proteins_100g":3.1}}}
            """.trimIndent(),
            barcode = "1111111111111",
        )!!.facts

        // 1000 / 4.184 = 239.005…, half-up (amendment A4) → 239; derived, so below 1.0 confidence.
        assertThat(facts.energyKcal.value).isEqualTo(239.0)
        assertThat(facts.energyKcal.confidence).isEqualTo(0.85)
        assertThat(facts.energyKj.value).isEqualTo(1000.0)
        assertThat(facts.basis).isEqualTo(MeasureBasis.PER_100G)
    }

    @Test
    fun off06_not_found_response_maps_to_null() {
        assertThat(product("not_found")).isNull()
    }

    @Test
    fun off07_nutriments_serialised_as_strings_are_still_read() {
        val facts = product(
            body = """
                {"status":1,"code":"2222222222222","product":{"product_name":"Stringy",
                "nutriments":{"energy-kcal_100g":"412","fat_100g":"17,4","salt_100g":"0.55"}}}
            """.trimIndent(),
            barcode = "2222222222222",
        )!!.facts

        assertThat(facts.energyKcal.value).isEqualTo(412.0)
        assertThat(facts.fatG.value).isEqualTo(17.4)
        assertThat(facts.saltG.value).isEqualTo(0.55)
    }

    @Test
    fun off08_sodium_only_product_derives_salt_and_vice_versa() {
        val facts = product(
            body = """
                {"status":1,"code":"3333333333333","product":{"product_name":"Sodium only",
                "nutriments":{"sodium_100g":0.4}}}
            """.trimIndent(),
            barcode = "3333333333333",
        )!!.facts

        assertThat(facts.sodiumG.value).isEqualTo(0.4)
        assertThat(facts.saltG.value).isWithin(1e-9).of(1.0)
        assertThat(facts.saltG.confidence).isEqualTo(0.85)
    }

    @Test
    fun off09_serving_size_without_a_weight_leaves_the_serving_unknown() {
        assertThat(OffMapper.servingAmountOf("1 slice")).isNull()
        assertThat(OffMapper.servingAmountOf(null)).isNull()
        assertThat(OffMapper.servingAmountOf("30 g")).isEqualTo(30.0)
        assertThat(OffMapper.servingAmountOf("1 bar (30,5 g)")).isEqualTo(30.5)
        assertThat(OffMapper.servingAmountOf("250ml")).isEqualTo(250.0)
    }

    @Test
    fun off10_a_body_with_a_product_but_status_zero_is_still_not_found() {
        val mapped = product(
            body = """{"status":0,"code":"4444444444444","product":{"product_name":"Ghost"}}""",
            barcode = "4444444444444",
        )

        assertThat(mapped).isNull()
    }

    private fun product(fixture: String): OffProduct? {
        val barcode = when (fixture) {
            "full_product" -> "3017620422003"
            "sparse_product" -> "4009900484169"
            else -> "0000000000000"
        }
        return product(loadOffFixture(fixture), barcode)
    }

    private fun product(body: String, barcode: String): OffProduct? =
        OffMapper.toProduct(OFF_JSON.decodeFromString(body), barcode)
}

/** Loads `app/src/test/resources/fixtures/off/<name>.json` off the classpath (rule R12-proof). */
internal fun loadOffFixture(name: String): String {
    val path = "fixtures/off/$name.json"
    val url = checkNotNull(OffMapper::class.java.classLoader).getResource(path)
        ?: error("Missing OFF fixture on the classpath: $path")
    return url.readText()
}
