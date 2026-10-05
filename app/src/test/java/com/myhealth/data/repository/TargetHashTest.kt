package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The `inputsHash` contract of PLAN P4.12: the digest must be stable across runs (so a restart
 * never triggers a spurious recompute), must change when an input changes, and must not depend on
 * the order the caller happened to build the field map in.
 */
class TargetHashTest {

    private fun fields(
        weight: String = "80.000",
        goalWeight: String = "78.000",
        dayType: String = "TRAINING",
    ): Map<String, String?> = mapOf(
        "profileUpdatedAt" to "1757000000000",
        "weight" to weight,
        "goalWeight" to goalWeight,
        "pace" to "-0.500",
        "neat" to "DESK",
        "dayType" to dayType,
        "plannedIds" to "4,9",
        "completedIds" to "11",
        "tdeeSource" to "ESTIMATED",
        "tdeeValue" to "2825.000",
    )

    @Test
    fun the_hash_is_stable_across_runs() {
        val first = TargetInputsHash.of(fields())
        val second = TargetInputsHash.of(fields())

        assertThat(first).isEqualTo(second)
        assertThat(first).hasLength(64)
        assertThat(first).matches("[0-9a-f]{64}")
        // A literal digest pins the algorithm and the canonical form: if either changes, every
        // stored snapshot silently recomputes once, and this test says so out loud.
        assertThat(TargetInputsHash.sha256("weight=80.000")).isEqualTo(
            "c632ea615f159af2a42d7c01aa9147f7f93c7758f4495f4d299cbabc66bffe3e",
        )
    }

    @Test
    fun the_hash_changes_when_the_weight_changes() {
        val before = TargetInputsHash.of(fields(weight = "80.000"))
        val after = TargetInputsHash.of(fields(weight = "79.400"))

        assertThat(after).isNotEqualTo(before)
    }

    @Test
    fun the_hash_changes_when_any_other_input_changes() {
        val base = TargetInputsHash.of(fields())

        assertThat(TargetInputsHash.of(fields(goalWeight = "75.000"))).isNotEqualTo(base)
        assertThat(TargetInputsHash.of(fields(dayType = "MATCH_DAY"))).isNotEqualTo(base)
    }

    @Test
    fun the_hash_is_independent_of_map_ordering() {
        val forward = fields()
        val reversed = forward.entries.reversed().associate { it.key to it.value }
        val shuffled = forward.entries.sortedBy { it.value }.associate { it.key to it.value }

        assertThat(TargetInputsHash.of(reversed)).isEqualTo(TargetInputsHash.of(forward))
        assertThat(TargetInputsHash.of(shuffled)).isEqualTo(TargetInputsHash.of(forward))
    }

    @Test
    fun the_canonical_form_sorts_by_key_and_renders_nulls_as_empty() {
        val canonical = TargetInputsHash.canonicalize(mapOf("b" to "2", "a" to null, "c" to "3"))

        assertThat(canonical).isEqualTo("a=|b=2|c=3")
    }

    @Test
    fun numbers_and_id_lists_are_rendered_canonically() {
        assertThat(TargetInputsHash.num(80.0)).isEqualTo("80.000")
        assertThat(TargetInputsHash.num(80.0000001)).isEqualTo("80.000")
        assertThat(TargetInputsHash.num(null)).isEmpty()
        // Query order must not look like a plan change.
        assertThat(TargetInputsHash.ids(listOf(9L, 4L))).isEqualTo(TargetInputsHash.ids(listOf(4L, 9L)))
        assertThat(TargetInputsHash.ids(emptyList())).isEmpty()
    }
}
