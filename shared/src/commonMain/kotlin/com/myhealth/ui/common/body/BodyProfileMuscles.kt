package com.myhealth.ui.common.body

import com.myhealth.domain.model.BodySegmentId
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.ui.common.body.BodyShapes.roundedBlock
import com.myhealth.ui.common.body.BodyShapes.smoothClosed

/**
 * The muscle regions of the **profile** figure (P18.1), in each segment's own local frame, on the
 * near (the subject's right) limbs only — the far limbs are silhouette, not surface.
 *
 * Fifteen of the sixteen [MuscleGroup]s read edge-on: the back half of the torso carries traps,
 * lats and the erectors, the front half the pec edge, the abs column and the flank, and the limbs
 * carry front/back pairs (front and rear deltoid, biceps and triceps, quads and hamstrings, the
 * calf belly). Only `ADDUCTORS` is genuinely invisible from the side — it is on the *inside* of
 * the far thigh — and is therefore absent; `an04` pins "every segment, at least ten groups".
 */
internal object BodyProfileMuscles {

    val REGIONS: Map<BodySegmentId, Map<MuscleGroup, List<MusclePaths.Polygon>>> = mapOf(
        BodySegmentId.TORSO to mapOf(
            MuscleGroup.TRAPS to listOf(TRAPS_SIDE),
            MuscleGroup.LATS to listOf(LAT_SIDE),
            MuscleGroup.LOWER_BACK to listOf(LOWER_BACK_SIDE),
            MuscleGroup.CHEST to listOf(PEC_SIDE),
            MuscleGroup.ABS to RECTUS_SIDE,
            MuscleGroup.OBLIQUES to listOf(OBLIQUE_SIDE),
        ),
        BodySegmentId.PELVIS to mapOf(MuscleGroup.GLUTES to listOf(GLUTE_SIDE)),
        BodySegmentId.UPPER_ARM_R to mapOf(
            MuscleGroup.SHOULDERS_FRONT to listOf(DELTOID_FRONT_SIDE),
            MuscleGroup.SHOULDERS_REAR to listOf(DELTOID_REAR_SIDE),
            MuscleGroup.BICEPS to listOf(BICEPS_SIDE),
            MuscleGroup.TRICEPS to listOf(TRICEPS_SIDE),
        ),
        BodySegmentId.FOREARM_R to mapOf(MuscleGroup.FOREARMS to listOf(FOREARM_SIDE)),
        BodySegmentId.THIGH_R to mapOf(
            MuscleGroup.QUADS to listOf(QUAD_SIDE),
            MuscleGroup.HAMSTRINGS to listOf(HAM_SIDE),
        ),
        BodySegmentId.SHANK_R to mapOf(MuscleGroup.CALVES to listOf(CALF_SIDE)),
    )
}

// ---- torso, seen edge-on ----------------------------------------------------------------------

private val TRAPS_SIDE = smoothClosed(
    listOf(
        BodyPoint(1.5f, -1.5f), BodyPoint(-3f, -1f), BodyPoint(-8f, 2f), BodyPoint(-11f, 9f),
        BodyPoint(-10.5f, 18f), BodyPoint(-7f, 21f), BodyPoint(-2f, 16f), BodyPoint(1f, 7f),
    ),
)

private val LAT_SIDE = smoothClosed(
    listOf(
        BodyPoint(-0.5f, 19f), BodyPoint(-5f, 18f), BodyPoint(-9.5f, 23f), BodyPoint(-11.5f, 32f),
        BodyPoint(-10.5f, 43f), BodyPoint(-7f, 49f), BodyPoint(-3f, 43f), BodyPoint(-1.5f, 32f),
    ),
)

private val LOWER_BACK_SIDE = smoothClosed(
    listOf(
        BodyPoint(-0.5f, 52f), BodyPoint(-5f, 51.5f), BodyPoint(-8.5f, 56f), BodyPoint(-9.5f, 66f),
        BodyPoint(-8f, 75f), BodyPoint(-4f, 77f), BodyPoint(-1f, 70f), BodyPoint(-0.5f, 60f),
    ),
)

private val PEC_SIDE = smoothClosed(
    listOf(
        BodyPoint(2f, 7f), BodyPoint(5f, 4.5f), BodyPoint(9f, 6.5f), BodyPoint(11f, 13f),
        BodyPoint(10.5f, 22f), BodyPoint(7.5f, 26.5f), BodyPoint(4f, 24.5f), BodyPoint(2.5f, 16f),
    ),
)

/** The rectus abdominis edge-on: one column of three blocks, not two. */
private val RECTUS_SIDE: List<MusclePaths.Polygon> = listOf(33f, 44f, 55f).map { top ->
    roundedBlock(2.5f, top, 8.8f, top + 9f, radius = 2.2f)
}

private val OBLIQUE_SIDE = smoothClosed(
    listOf(
        BodyPoint(2f, 34f), BodyPoint(-2.5f, 36f), BodyPoint(-4.5f, 46f), BodyPoint(-3.5f, 60f),
        BodyPoint(-0.5f, 69f), BodyPoint(2.5f, 63f), BodyPoint(3f, 50f),
    ),
)

private val GLUTE_SIDE = smoothClosed(
    listOf(
        BodyPoint(-1.5f, 2f), BodyPoint(-6f, 1f), BodyPoint(-10.5f, 5f), BodyPoint(-12f, 12f),
        BodyPoint(-10.5f, 20f), BodyPoint(-6f, 23f), BodyPoint(-2.5f, 16f), BodyPoint(-1.5f, 8f),
    ),
)

// ---- the near arm -----------------------------------------------------------------------------

private val DELTOID_FRONT_SIDE = smoothClosed(
    listOf(
        BodyPoint(0f, -1.5f), BodyPoint(2f, 0f), BodyPoint(4.4f, 6f), BodyPoint(4.4f, 15f),
        BodyPoint(1.5f, 18.5f), BodyPoint(0f, 11f),
    ),
)

private val DELTOID_REAR_SIDE = smoothClosed(
    listOf(
        BodyPoint(-0.6f, -1.5f), BodyPoint(-3f, 0f), BodyPoint(-5.2f, 6f), BodyPoint(-5.2f, 15f),
        BodyPoint(-2.5f, 18.5f), BodyPoint(-0.6f, 11f),
    ),
)

private val BICEPS_SIDE = smoothClosed(
    listOf(
        BodyPoint(0.5f, 20f), BodyPoint(2.8f, 23f), BodyPoint(4f, 30f), BodyPoint(2.8f, 37.5f),
        BodyPoint(0.5f, 39.5f), BodyPoint(-0.2f, 30f),
    ),
)

private val TRICEPS_SIDE = smoothClosed(
    listOf(
        BodyPoint(-0.8f, 20f), BodyPoint(-3.2f, 23f), BodyPoint(-4.6f, 30f), BodyPoint(-3.4f, 37.5f),
        BodyPoint(-0.8f, 39.5f), BodyPoint(-0.2f, 30f),
    ),
)

private val FOREARM_SIDE = smoothClosed(
    listOf(
        BodyPoint(0f, -1.5f), BodyPoint(2.4f, 2f), BodyPoint(2.6f, 12f), BodyPoint(1.6f, 24f),
        BodyPoint(0.4f, 32f), BodyPoint(-1.6f, 29f), BodyPoint(-3.4f, 18f), BodyPoint(-3.2f, 6f),
    ),
)

// ---- the near leg -----------------------------------------------------------------------------

private val QUAD_SIDE = smoothClosed(
    listOf(
        BodyPoint(0f, -3f), BodyPoint(3.5f, 0.5f), BodyPoint(5.8f, 12f), BodyPoint(5.8f, 26f),
        BodyPoint(4f, 39f), BodyPoint(1.5f, 44f), BodyPoint(0.5f, 30f), BodyPoint(0f, 14f),
    ),
)

private val HAM_SIDE = smoothClosed(
    listOf(
        BodyPoint(-1f, -1.5f), BodyPoint(-5f, 2f), BodyPoint(-7.6f, 14f), BodyPoint(-7.4f, 28f),
        BodyPoint(-5.4f, 39f), BodyPoint(-2.2f, 43f), BodyPoint(-1.4f, 28f), BodyPoint(-1f, 12f),
    ),
)

private val CALF_SIDE = smoothClosed(
    listOf(
        BodyPoint(-1.2f, 3f), BodyPoint(-3.6f, 6f), BodyPoint(-5.2f, 13f), BodyPoint(-4.8f, 22f),
        BodyPoint(-2.8f, 28f), BodyPoint(-1f, 22f), BodyPoint(-0.8f, 10f),
    ),
)
