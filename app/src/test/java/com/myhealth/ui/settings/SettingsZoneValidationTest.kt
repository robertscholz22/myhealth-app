package com.myhealth.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Settings "Heart-rate zones" section's pure validation logic (PLAN §4.2, P14.6). */
class SettingsZoneValidationTest {

    @Test
    fun zui09_non_ascending_or_partial_bounds_are_rejected() {
        // Non-ascending: all four present but out of order.
        assertThat(hrZoneBoundsStatus(listOf(160, 148, 162, 176))).isEqualTo(HrZoneBoundsStatus.INVALID)

        // Partial: only three of four typed.
        assertThat(hrZoneBoundsStatus(listOf(134, 148, null, 176))).isEqualTo(HrZoneBoundsStatus.INVALID)
        assertThat(hrZoneBoundsStatus(listOf(134, null, null, null))).isEqualTo(HrZoneBoundsStatus.INVALID)

        // Equal (not strictly ascending) is also rejected.
        assertThat(hrZoneBoundsStatus(listOf(134, 148, 148, 176))).isEqualTo(HrZoneBoundsStatus.INVALID)

        // Round trip: encoding a partial set and reparsing it stays partial, never becomes valid.
        val encoded = encodeHrZoneBoundsSlots(listOf(134, 148, null, 176))
        assertThat(hrZoneBoundsStatus(parseHrZoneBoundsSlots(encoded))).isEqualTo(HrZoneBoundsStatus.INVALID)
    }

    @Test
    fun zui10_valid_ascending_bounds_are_accepted() {
        val slots = listOf(134, 148, 162, 176)
        assertThat(hrZoneBoundsStatus(slots)).isEqualTo(HrZoneBoundsStatus.VALID)

        val encoded = encodeHrZoneBoundsSlots(slots)
        assertThat(encoded).isEqualTo("[134,148,162,176]")
        assertThat(parseHrZoneBoundsSlots(encoded)).isEqualTo(slots)

        // All blank is a valid "no override" state — not an error.
        assertThat(hrZoneBoundsStatus(listOf(null, null, null, null))).isEqualTo(HrZoneBoundsStatus.EMPTY)
        assertThat(encodeHrZoneBoundsSlots(listOf(null, null, null, null))).isNull()
    }
}
