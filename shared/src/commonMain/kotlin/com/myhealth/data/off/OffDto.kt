package com.myhealth.data.off

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The slice of an Open Food Facts v2 product response this app asks for (PLAN P4.10):
 * `?fields=product_name,brands,quantity,serving_size,image_url,nutriments`.
 *
 * OFF adds and renames fields constantly, so [OFF_JSON] ignores unknown keys (R-proof against a
 * schema change breaking a scan) and every field here is optional.
 */
@Serializable
data class OffResponse(
    /** `1` when the product exists, `0` when it does not (the "not found" body is a 200). */
    val status: Int = 0,
    @SerialName("status_verbose") val statusVerbose: String? = null,
    val code: String? = null,
    val product: OffProductDto? = null,
)

@Serializable
data class OffProductDto(
    @SerialName("product_name") val productName: String? = null,
    val brands: String? = null,
    /** Net quantity as printed, e.g. `"500 ml"` or `"1 kg"`. */
    val quantity: String? = null,
    @SerialName("serving_size") val servingSize: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    /**
     * Flat map of nutriment keys to numbers — values arrive as JSON numbers *or* strings
     * depending on the product, so they are kept as [JsonElement] and read through [numberOf].
     */
    val nutriments: Map<String, JsonElement> = emptyMap(),
)

/** Reads a nutriment value whether OFF serialised it as a number or as a string. */
fun Map<String, JsonElement>.numberOf(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.content.trim().replace(',', '.').toDoubleOrNull()
}

/** The one JSON configuration used for OFF bodies (also used by `OffMapperTest`'s fixtures). */
val OFF_JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
