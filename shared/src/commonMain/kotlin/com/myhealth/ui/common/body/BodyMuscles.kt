package com.myhealth.ui.common.body

import com.myhealth.domain.model.BodySegmentId
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.ui.common.body.BodyShapes.mirrorX
import com.myhealth.ui.common.body.BodyShapes.roundedBlock
import com.myhealth.ui.common.body.BodyShapes.smoothClosed

/**
 * The muscle regions drawn on each segment (P15.1), in that segment's **local frame** — organic
 * blobs rather than rectangles, so a highlighted group reads as a muscle belly on the figure.
 *
 * Only the subject's left arm/leg is written down; `BodyGeometry.segments` mirrors it onto the
 * right, and `BodyGeometry.mirrored` flips the whole thing for the back view. A group that lives on
 * a central segment (chest, abs, traps, lats, glutes …) lists both halves here, because the torso
 * and pelvis are not mirrored per side.
 *
 * Every [MuscleGroup] appears on the side(s) its `side` property declares — pinned by `bf03`.
 */
internal object BodyMuscles {

    /** Groups visible from the front (`BodySide.FRONT` or `BOTH`). */
    val FRONT: Map<BodySegmentId, Map<MuscleGroup, List<MusclePaths.Polygon>>> = mapOf(
        BodySegmentId.TORSO to mapOf(
            MuscleGroup.CHEST to pair(PEC),
            MuscleGroup.ABS to RECTUS_BLOCKS,
            MuscleGroup.OBLIQUES to pair(OBLIQUE),
        ),
        BodySegmentId.UPPER_ARM_L to mapOf(
            MuscleGroup.SHOULDERS_FRONT to listOf(DELTOID),
            MuscleGroup.BICEPS to listOf(BICEPS),
        ),
        BodySegmentId.FOREARM_L to mapOf(MuscleGroup.FOREARMS to listOf(FOREARM_BELLY)),
        BodySegmentId.THIGH_L to mapOf(
            MuscleGroup.QUADS to listOf(QUAD_OUTER, QUAD_INNER),
            MuscleGroup.ADDUCTORS to listOf(ADDUCTOR),
        ),
        BodySegmentId.SHANK_L to mapOf(MuscleGroup.CALVES to listOf(TIBIALIS)),
    )

    /** Groups visible from the back (`BodySide.BACK` or `BOTH`). */
    val BACK: Map<BodySegmentId, Map<MuscleGroup, List<MusclePaths.Polygon>>> = mapOf(
        BodySegmentId.TORSO to mapOf(
            MuscleGroup.TRAPS to listOf(TRAPS),
            MuscleGroup.LATS to pair(LAT),
            MuscleGroup.LOWER_BACK to listOf(LOWER_BACK),
        ),
        BodySegmentId.PELVIS to mapOf(MuscleGroup.GLUTES to pair(GLUTE)),
        BodySegmentId.UPPER_ARM_L to mapOf(
            MuscleGroup.SHOULDERS_REAR to listOf(DELTOID),
            MuscleGroup.TRICEPS to listOf(TRICEPS),
        ),
        BodySegmentId.FOREARM_L to mapOf(MuscleGroup.FOREARMS to listOf(FOREARM_BELLY)),
        BodySegmentId.THIGH_L to mapOf(MuscleGroup.HAMSTRINGS to listOf(HAM_OUTER, HAM_INNER)),
        BodySegmentId.SHANK_L to mapOf(MuscleGroup.CALVES to listOf(GASTROC_LATERAL, GASTROC_MEDIAL)),
    )

    private fun pair(left: MusclePaths.Polygon) = listOf(left, mirrorX(left))
}

// ---- torso, front -----------------------------------------------------------------------------

private val PEC = smoothClosed(
    listOf(
        BodyPoint(-2.5f, 8f), BodyPoint(-9f, 6f), BodyPoint(-15.5f, 8.5f), BodyPoint(-18.5f, 15f),
        BodyPoint(-16f, 24f), BodyPoint(-9f, 26.5f), BodyPoint(-3f, 23f),
    ),
)

/** The rectus abdominis as its six blocks — three rows, two columns. */
private val RECTUS_BLOCKS: List<MusclePaths.Polygon> = listOf(32f, 43f, 54f).flatMap { top ->
    listOf(
        roundedBlock(-8.5f, top, -1f, top + 9.5f, radius = 2.4f),
        roundedBlock(1f, top, 8.5f, top + 9.5f, radius = 2.4f),
    )
}

private val OBLIQUE = smoothClosed(
    listOf(
        BodyPoint(-9.5f, 32f), BodyPoint(-15f, 34f), BodyPoint(-17f, 45f), BodyPoint(-15.5f, 58f),
        BodyPoint(-12f, 70f), BodyPoint(-8f, 65f), BodyPoint(-8.8f, 48f),
    ),
)

// ---- torso and pelvis, back -------------------------------------------------------------------

private val TRAPS = smoothClosed(
    listOf(
        BodyPoint(0f, 2f), BodyPoint(-11f, 5f), BodyPoint(-16.5f, 13f), BodyPoint(-10f, 23f),
        BodyPoint(0f, 31f), BodyPoint(10f, 23f), BodyPoint(16.5f, 13f), BodyPoint(11f, 5f),
    ),
)

/** The lat's V: wide under the armpit, tapering into the waist. */
private val LAT = smoothClosed(
    listOf(
        BodyPoint(-20f, 16f), BodyPoint(-21f, 28f), BodyPoint(-18.5f, 42f), BodyPoint(-13f, 54f),
        BodyPoint(-8.5f, 56f), BodyPoint(-8f, 45f), BodyPoint(-11f, 32f), BodyPoint(-15f, 21f),
    ),
)

private val LOWER_BACK = smoothClosed(
    listOf(
        BodyPoint(0f, 52f), BodyPoint(-8.5f, 55f), BodyPoint(-10.5f, 65f), BodyPoint(-8f, 76f),
        BodyPoint(0f, 79f), BodyPoint(8f, 76f), BodyPoint(10.5f, 65f), BodyPoint(8.5f, 55f),
    ),
)

private val GLUTE = smoothClosed(
    listOf(
        BodyPoint(-1.5f, 1f), BodyPoint(-9f, 0f), BodyPoint(-15f, 5f), BodyPoint(-15.5f, 14f),
        BodyPoint(-9.5f, 21f), BodyPoint(-3f, 20f), BodyPoint(-1f, 10f),
    ),
)

// ---- arms -------------------------------------------------------------------------------------

private val DELTOID = smoothClosed(
    listOf(
        BodyPoint(-5.8f, 2f), BodyPoint(-8.2f, 9f), BodyPoint(-8.4f, 17f), BodyPoint(-5.6f, 21f),
        BodyPoint(-1.6f, 19f), BodyPoint(1.4f, 10f), BodyPoint(3.8f, 2f), BodyPoint(2.4f, -0.5f),
        BodyPoint(-1.4f, -1.2f),
    ),
)

private val BICEPS = smoothClosed(
    listOf(
        BodyPoint(-6.6f, 20f), BodyPoint(-7.6f, 27f), BodyPoint(-7f, 35f), BodyPoint(-4.8f, 39.5f),
        BodyPoint(-2.4f, 36f), BodyPoint(-1.4f, 28f), BodyPoint(-2f, 21f), BodyPoint(-4.4f, 18.5f),
    ),
)

private val TRICEPS = smoothClosed(
    listOf(
        BodyPoint(-6.8f, 19f), BodyPoint(-7.8f, 27f), BodyPoint(-7f, 36f), BodyPoint(-4.6f, 40f),
        BodyPoint(-2f, 36f), BodyPoint(-1f, 27f), BodyPoint(-1.8f, 19f), BodyPoint(-4.4f, 17f),
    ),
)

private val FOREARM_BELLY = smoothClosed(
    listOf(
        BodyPoint(-3f, -2f), BodyPoint(-4.8f, 5f), BodyPoint(-5.2f, 14f), BodyPoint(-4.4f, 24f),
        BodyPoint(-3f, 32f), BodyPoint(-1f, 34f), BodyPoint(0.2f, 25f), BodyPoint(1.2f, 16f),
        BodyPoint(2.4f, 7f), BodyPoint(2.8f, -2f),
    ),
)

// ---- legs -------------------------------------------------------------------------------------

private val QUAD_OUTER = smoothClosed(
    listOf(
        BodyPoint(-7.4f, -1f), BodyPoint(-9.2f, 10f), BodyPoint(-8.8f, 24f), BodyPoint(-7.2f, 34f),
        BodyPoint(-5.4f, 40f), BodyPoint(-3.2f, 33f), BodyPoint(-2.6f, 17f), BodyPoint(-3.4f, 1f),
    ),
)

private val QUAD_INNER = smoothClosed(
    listOf(
        BodyPoint(-2.2f, -1f), BodyPoint(-2f, 17f), BodyPoint(-1.8f, 32f), BodyPoint(-0.8f, 41f),
        BodyPoint(1.6f, 36f), BodyPoint(2.8f, 22f), BodyPoint(3.4f, 8f), BodyPoint(3f, -1.5f),
    ),
)

private val ADDUCTOR = smoothClosed(
    listOf(
        BodyPoint(3.8f, -2f), BodyPoint(3.6f, 8f), BodyPoint(3.4f, 18f), BodyPoint(4.4f, 25f),
        BodyPoint(5.8f, 19f), BodyPoint(6.4f, 9f), BodyPoint(6.8f, -2f),
    ),
)

private val HAM_OUTER = smoothClosed(
    listOf(
        BodyPoint(-7.4f, 0f), BodyPoint(-9.2f, 11f), BodyPoint(-8.8f, 25f), BodyPoint(-7f, 35f),
        BodyPoint(-5f, 41f), BodyPoint(-3f, 33f), BodyPoint(-2.6f, 18f), BodyPoint(-3.4f, 2f),
    ),
)

private val HAM_INNER = smoothClosed(
    listOf(
        BodyPoint(-2.2f, 0f), BodyPoint(-2f, 17f), BodyPoint(-1.8f, 32f), BodyPoint(-0.8f, 41f),
        BodyPoint(2f, 36f), BodyPoint(3.6f, 22f), BodyPoint(4.6f, 8f), BodyPoint(4.2f, -1f),
    ),
)

/** Front of the shank: the tibialis strip beside the shin bone. */
private val TIBIALIS = smoothClosed(
    listOf(
        BodyPoint(-1.4f, 2f), BodyPoint(-2.8f, 12f), BodyPoint(-3f, 24f), BodyPoint(-2f, 32f),
        BodyPoint(-0.2f, 30f), BodyPoint(0.8f, 20f), BodyPoint(1.8f, 9f), BodyPoint(2f, 1f),
    ),
)

private val GASTROC_LATERAL = smoothClosed(
    listOf(
        BodyPoint(-5.4f, 4f), BodyPoint(-6.4f, 12f), BodyPoint(-5.4f, 22f), BodyPoint(-3.2f, 28f),
        BodyPoint(-1.8f, 21f), BodyPoint(-2.2f, 11f), BodyPoint(-3f, 4f),
    ),
)

private val GASTROC_MEDIAL = smoothClosed(
    listOf(
        BodyPoint(-1f, 4f), BodyPoint(-0.8f, 13f), BodyPoint(-1.2f, 23f), BodyPoint(-0.2f, 29f),
        BodyPoint(2f, 22f), BodyPoint(3f, 12f), BodyPoint(3.2f, 4f),
    ),
)
