package com.myhealth.data.ocr

import com.myhealth.domain.engine.label.OcrLine

/**
 * Turns a text recogniser's lines into the parser's platform-free [OcrLine]s (PLAN P4.8).
 *
 * The recogniser-specific part is a thin adapter to [RawLine]s (ML Kit's `Text` on Android,
 * `MlKitOcrLines.kt`; Vision on iOS, P22); everything the parser depends on — dropping
 * boxless/blank lines and ordering them the way a human reads a label, top to bottom and then
 * left to right — lives in the pure [map] below and is unit-tested through [RawLine] stand-ins
 * (`OcrLineMapperTest`).
 */
object OcrLineMapper {

    /** A recognised line before it has a usable box: `bounds` is null when ML Kit had none. */
    data class RawLine(val text: String, val bounds: Bounds?)

    /** The four pixel edges of a recognised line's bounding box, in image coordinates. */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Lines whose vertical centres are within this many pixels count as the same row. */
    private const val ROW_TOLERANCE = 12

    /**
     * Pure part: skips lines without a box or without text, then sorts top → bottom and, within
     * one row, left → right — the order §3.6 assumes when it reads a per-100 column before a
     * per-serving one. Rows are found by quantising the vertical centre into [ROW_TOLERANCE]-px
     * bands, which keeps the comparator transitive (a "within N px" comparator is not, and
     * `sortedWith` would reject it).
     */
    fun map(raw: List<RawLine>): List<OcrLine> = raw
        .mapNotNull { line ->
            val bounds = line.bounds ?: return@mapNotNull null
            if (line.text.isBlank()) return@mapNotNull null
            OcrLine(
                text = line.text,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
            )
        }
        .sortedWith(compareBy({ it.rowBand() }, { it.left }, { it.top }))

    private fun OcrLine.rowBand(): Int = ((top + bottom) / 2) / ROW_TOLERANCE
}
