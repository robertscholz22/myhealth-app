package com.myhealth.data.ocr

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.ocr.OcrLineMapper.Bounds
import com.myhealth.data.ocr.OcrLineMapper.RawLine
import org.junit.Test

/**
 * The pure half of [OcrLineMapper] (PLAN P4.8): ML Kit's `Text.Line` list carries boxes that can
 * be null and arrives in block order, while §3.6 expects boxed lines in reading order. Driven
 * through [RawLine] stand-ins so no `android.graphics.Rect` (or ML Kit) is involved.
 */
class OcrLineMapperTest {

    @Test
    fun ocrmap01_drops_lines_without_a_box_or_without_text() {
        val mapped = OcrLineMapper.map(
            listOf(
                RawLine("Energie 1724 kJ", Bounds(10, 100, 320, 130)),
                RawLine("Fett 17,4 g", null),
                RawLine("   ", Bounds(10, 200, 320, 230)),
                RawLine("Eiweiss 7,8 g", Bounds(10, 300, 320, 330)),
            ),
        )

        assertThat(mapped.map { it.text }).containsExactly("Energie 1724 kJ", "Eiweiss 7,8 g").inOrder()
        assertThat(mapped.first().left).isEqualTo(10)
        assertThat(mapped.first().top).isEqualTo(100)
        assertThat(mapped.first().right).isEqualTo(320)
        assertThat(mapped.first().bottom).isEqualTo(130)
    }

    @Test
    fun ocrmap02_sorts_top_to_bottom_regardless_of_block_order() {
        val mapped = OcrLineMapper.map(
            listOf(
                RawLine("Salz 0,55 g", Bounds(10, 520, 300, 550)),
                RawLine("Nahrwerte je 100 g", Bounds(10, 40, 300, 70)),
                RawLine("Kohlenhydrate 53 g", Bounds(10, 300, 300, 330)),
            ),
        )

        assertThat(mapped.map { it.text })
            .containsExactly("Nahrwerte je 100 g", "Kohlenhydrate 53 g", "Salz 0,55 g")
            .inOrder()
    }

    @Test
    fun ocrmap03_orders_one_row_left_to_right_even_when_the_boxes_are_a_few_pixels_apart() {
        // A two-column label: keyword, per-100 value, per-portion value — same row, tops off by a
        // few pixels, which must not reorder them (§3.6.1 step 4 reads the columns left to right).
        val mapped = OcrLineMapper.map(
            listOf(
                RawLine("124", Bounds(430, 303, 480, 331)),
                RawLine("412", Bounds(300, 300, 350, 330)),
                RawLine("Energie", Bounds(10, 302, 180, 332)),
                RawLine("Fett", Bounds(10, 360, 180, 390)),
            ),
        )

        assertThat(mapped.map { it.text }).containsExactly("Energie", "412", "124", "Fett").inOrder()
    }
}
