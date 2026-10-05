package com.myhealth.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The `WorkoutStructure` JSON codec of PLAN §3.11 (`iv08`, `iv09`). The builder that produces
 * these structures arrives with `IntervalCatalog` in P14.3; here only the model and its codec are
 * under test.
 */
class WorkoutStructureTest {

    /** 15 min warm-up, 5 × (1000 m @ I pace / 2:00 jog), 10 min cool-down — the `iv01` shape. */
    private fun fiveByThousand() = WorkoutStructure(
        templateId = "RUN_1000_I",
        steps = listOf(
            WorkoutStep(
                kind = WorkoutStepKind.WARMUP,
                durationSec = 900,
                target = WorkoutTargetKind.ZONE,
                zone = 2,
            ),
            WorkoutStep(
                kind = WorkoutStepKind.REPEAT,
                repeat = 5,
                children = listOf(
                    WorkoutStep(
                        kind = WorkoutStepKind.WORK,
                        distanceMeters = 1_000.0,
                        target = WorkoutTargetKind.PACE,
                        zone = 5,
                        paceLowSecPerKm = 229,
                        paceHighSecPerKm = 239,
                    ),
                    WorkoutStep(kind = WorkoutStepKind.RECOVERY, durationSec = 120, zone = 1),
                ),
            ),
            WorkoutStep(
                kind = WorkoutStepKind.COOLDOWN,
                durationSec = 600,
                target = WorkoutTargetKind.ZONE,
                zone = 1,
                note = "easy",
            ),
        ),
    )

    @Test
    fun iv08_structure_json_round_trips() {
        val structure = fiveByThousand()

        val json = WorkoutStructureCodec.encode(structure)
        val decoded = WorkoutStructureCodec.decode(json)

        assertThat(decoded).isEqualTo(structure)
        assertThat(checkNotNull(decoded).steps).hasSize(3)
        assertThat(decoded.steps[1].children.first().paceLowSecPerKm).isEqualTo(229)
        assertThat(json).contains("RUN_1000_I")
    }

    @Test
    fun iv09_unknown_version_decodes_to_null() {
        val future = WorkoutStructureCodec.encode(fiveByThousand().copy(version = 99))

        assertThat(WorkoutStructureCodec.decode(future)).isNull()
        // Nothing readable at all is null too, rather than an exception on a screen's render pass.
        assertThat(WorkoutStructureCodec.decode("{not json")).isNull()
        assertThat(WorkoutStructureCodec.decode(null)).isNull()
        assertThat(WorkoutStructureCodec.decode("   ")).isNull()
    }

    @Test
    fun nesting_deeper_than_one_repeat_level_is_rejected() {
        val nested = WorkoutStructure(
            steps = listOf(
                WorkoutStep(
                    kind = WorkoutStepKind.REPEAT,
                    repeat = 2,
                    children = listOf(
                        WorkoutStep(
                            kind = WorkoutStepKind.REPEAT,
                            repeat = 10,
                            children = listOf(
                                WorkoutStep(kind = WorkoutStepKind.WORK, durationSec = 30),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertThat(WorkoutStructureCodec.isNestingValid(nested)).isFalse()
        assertThat(WorkoutStructureCodec.decode(WorkoutStructureCodec.encode(nested))).isNull()
        // One level — `2 × (10 × 30/30)` written as a repeat of plain steps — is fine.
        assertThat(WorkoutStructureCodec.isNestingValid(fiveByThousand())).isTrue()
    }
}
