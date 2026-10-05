package com.myhealth.domain.engine.label

import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.NutritionFactsDraft
import com.myhealth.domain.util.EngineWarning

/**
 * One line of recognised text with its bounding box, exactly as PLAN §3.6 specifies it. Kept
 * Android-free: the ML Kit side (P4.8) maps `Text.Line` + `boundingBox` onto this.
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2

    /**
     * The §3.6.1 step-4 x-estimate for the character at [charOffset] of a line whose text is
     * [length] characters long: `left + (right - left) * charOffset / text.length`.
     */
    fun xAt(charOffset: Int, length: Int): Int =
        if (length <= 0) centerX else left + ((right - left).toDouble() * charOffset / length).toInt()
}

/** Result of [NutritionLabelParser.parse] (§3.6). */
sealed interface LabelParseResult {
    data class Success(
        val draft: NutritionFactsDraft,
        val warnings: List<EngineWarning>,
    ) : LabelParseResult

    data class Failed(val reason: EngineWarningCode) : LabelParseResult
}
