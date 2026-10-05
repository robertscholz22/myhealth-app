package com.myhealth.ui.common.body

import com.myhealth.domain.model.BodySegmentId
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.ui.common.body.BodyShapes.ellipse
import com.myhealth.ui.common.body.BodyShapes.mirrorX
import com.myhealth.ui.common.body.BodyShapes.smoothClosed

/**
 * The skeleton's *bones* (P15.1): where every joint sits and what every part looks like. Muscle
 * regions live in `BodyMuscles`; this file only knows about the silhouette.
 *
 * All of it is hand-tuned in the normalised **100 × 220** box of [MusclePaths]: head top at y ≈ 3.5,
 * soles at y ≈ 217, shoulders at x ≈ 21…79. Only the subject's **left** side is written down — the
 * right is [mirrorX] of it, and the back skeleton is [mirrored] as a whole (a person seen from
 * behind has their left hand on the viewer's right).
 */
internal object BodyGeometry {

    // ---- joints -------------------------------------------------------------------------------
    // The root pivot is a position in the box; every other pivot is in its parent's local frame.

    private val TORSO_PIVOT = BodyPoint(50f, 36f) // centre of the shoulder line
    private val NECK_PIVOT = BodyPoint(0f, -2f)
    private val HEAD_PIVOT = BodyPoint(0f, -4f)
    private val SHOULDER_PIVOT = BodyPoint(-19.5f, 4.5f)
    private val ELBOW_PIVOT = BodyPoint(-4.4f, 39.5f)
    private val WRIST_PIVOT = BodyPoint(-1.2f, 34f)
    private val PELVIS_PIVOT = BodyPoint(0f, 74f)
    private val HIP_PIVOT = BodyPoint(-8.5f, 16f)
    private val KNEE_PIVOT = BodyPoint(-2f, 46f)
    private val ANKLE_PIVOT = BodyPoint(-0.7f, 36f)

    // ---- outlines (local frames, pivot at the origin) -------------------------------------------

    /** Sloping shoulders, a tapered waist, hips flaring back out. */
    private val TORSO = smoothClosed(
        listOf(
            BodyPoint(-6f, -2f), BodyPoint(-15f, 0f), BodyPoint(-21f, 5f), BodyPoint(-23f, 13f),
            BodyPoint(-21f, 26f), BodyPoint(-18f, 42f), BodyPoint(-16f, 56f), BodyPoint(-17.5f, 68f),
            BodyPoint(-16f, 76f), BodyPoint(-9f, 80f), BodyPoint(0f, 81f), BodyPoint(9f, 80f),
            BodyPoint(16f, 76f), BodyPoint(17.5f, 68f), BodyPoint(16f, 56f), BodyPoint(18f, 42f),
            BodyPoint(21f, 26f), BodyPoint(23f, 13f), BodyPoint(21f, 5f), BodyPoint(15f, 0f),
            BodyPoint(6f, -2f),
        ),
    )

    private val NECK = smoothClosed(
        listOf(
            BodyPoint(-5.6f, 2f), BodyPoint(-5f, -2f), BodyPoint(-4.4f, -6f),
            BodyPoint(4.4f, -6f), BodyPoint(5f, -2f), BodyPoint(5.6f, 2f),
        ),
        steps = 4,
    )

    /** Slightly egg-shaped rather than a circle, with a narrower jaw. */
    private val HEAD = smoothClosed(
        listOf(
            BodyPoint(0f, -26.5f), BodyPoint(7f, -24f), BodyPoint(10.4f, -17f), BodyPoint(10.4f, -8f),
            BodyPoint(8f, -1f), BodyPoint(4f, 2.5f), BodyPoint(0f, 3f), BodyPoint(-4f, 2.5f),
            BodyPoint(-8f, -1f), BodyPoint(-10.4f, -8f), BodyPoint(-10.4f, -17f), BodyPoint(-7f, -24f),
        ),
    )

    /** Held a little away from the body: the centreline drifts outward from the shoulder down. */
    private val UPPER_ARM = smoothClosed(
        listOf(
            BodyPoint(-6.6f, 3f), BodyPoint(-8.6f, 11f), BodyPoint(-9f, 20f), BodyPoint(-8.6f, 30f),
            BodyPoint(-7.8f, 37f), BodyPoint(-6.4f, 42.5f), BodyPoint(-2.4f, 43f), BodyPoint(-0.6f, 34f),
            BodyPoint(1.4f, 22f), BodyPoint(3.6f, 11f), BodyPoint(5.4f, 3f), BodyPoint(4.6f, -0.5f),
            BodyPoint(0f, -2f),
        ),
    )

    private val FOREARM = smoothClosed(
        listOf(
            BodyPoint(-3.4f, -4f), BodyPoint(-5.4f, 4f), BodyPoint(-6f, 12f), BodyPoint(-5.4f, 22f),
            BodyPoint(-4f, 30f), BodyPoint(-2.6f, 36f), BodyPoint(-0.4f, 37f), BodyPoint(0.8f, 28f),
            BodyPoint(1.8f, 18f), BodyPoint(3.2f, 8f), BodyPoint(3.6f, -4f),
        ),
    )

    /** Reaches back over the wrist so the hand is part of the arm, not a pebble beside it. */
    private val HAND = ellipse(cx = -0.6f, cy = 4.2f, rx = 4.4f, ry = 9.2f, steps = 20)

    private val PELVIS = smoothClosed(
        listOf(
            BodyPoint(-14f, -6f), BodyPoint(-17.5f, 0f), BodyPoint(-18f, 9f), BodyPoint(-15.5f, 18f),
            BodyPoint(-10f, 24f), BodyPoint(0f, 26f), BodyPoint(10f, 24f), BodyPoint(15.5f, 18f),
            BodyPoint(18f, 9f), BodyPoint(17.5f, 0f), BodyPoint(14f, -6f),
        ),
    )

    private val THIGH = smoothClosed(
        listOf(
            BodyPoint(-8.5f, -4f), BodyPoint(-10f, 8f), BodyPoint(-9.6f, 22f), BodyPoint(-8.2f, 34f),
            BodyPoint(-7.2f, 43f), BodyPoint(-4.5f, 49f), BodyPoint(0.5f, 49f), BodyPoint(3.2f, 42f),
            BodyPoint(5f, 28f), BodyPoint(6.8f, 12f), BodyPoint(7.4f, -4f), BodyPoint(4.6f, -9f),
            BodyPoint(-4f, -9.5f),
        ),
    )

    private val SHANK = smoothClosed(
        listOf(
            BodyPoint(-3.8f, -6f), BodyPoint(-5f, 6f), BodyPoint(-6.6f, 16f), BodyPoint(-5f, 26f),
            BodyPoint(-3.6f, 33f), BodyPoint(-2f, 40f), BodyPoint(0.6f, 40f), BodyPoint(1.8f, 30f),
            BodyPoint(3f, 18f), BodyPoint(4.2f, 7f), BodyPoint(3.6f, -6f),
        ),
    )

    private val FOOT = smoothClosed(
        listOf(
            BodyPoint(-3.6f, -6f), BodyPoint(-5f, 0f), BodyPoint(-4.6f, 5f), BodyPoint(-1f, 8.5f),
            BodyPoint(3.6f, 8f), BodyPoint(5.8f, 4f), BodyPoint(5f, -6f),
        ),
        steps = 5,
    )

    /** The sixteen segments, with [muscles] (keyed by the *left* / central segment) hung on them. */
    fun segments(muscles: Map<BodySegmentId, Map<MuscleGroup, List<MusclePaths.Polygon>>>):
        List<BodySegment> {
        fun regions(id: BodySegmentId) = muscles[id].orEmpty()
        fun mirroredRegions(id: BodySegmentId) =
            regions(id).mapValues { (_, polygons) -> polygons.map(::mirrorX) }
        return listOf(
            BodySegment(BodySegmentId.TORSO, null, TORSO_PIVOT, TORSO, regions(BodySegmentId.TORSO)),
            BodySegment(BodySegmentId.NECK, BodySegmentId.TORSO, NECK_PIVOT, NECK),
            BodySegment(BodySegmentId.HEAD, BodySegmentId.NECK, HEAD_PIVOT, HEAD),
            BodySegment(
                BodySegmentId.PELVIS, BodySegmentId.TORSO, PELVIS_PIVOT, PELVIS,
                regions(BodySegmentId.PELVIS),
            ),
            BodySegment(
                BodySegmentId.UPPER_ARM_L, BodySegmentId.TORSO, SHOULDER_PIVOT, UPPER_ARM,
                regions(BodySegmentId.UPPER_ARM_L),
            ),
            BodySegment(
                BodySegmentId.UPPER_ARM_R, BodySegmentId.TORSO, SHOULDER_PIVOT.mirror(),
                mirrorX(UPPER_ARM), mirroredRegions(BodySegmentId.UPPER_ARM_L),
            ),
            BodySegment(
                BodySegmentId.FOREARM_L, BodySegmentId.UPPER_ARM_L, ELBOW_PIVOT, FOREARM,
                regions(BodySegmentId.FOREARM_L),
            ),
            BodySegment(
                BodySegmentId.FOREARM_R, BodySegmentId.UPPER_ARM_R, ELBOW_PIVOT.mirror(),
                mirrorX(FOREARM), mirroredRegions(BodySegmentId.FOREARM_L),
            ),
            BodySegment(BodySegmentId.HAND_L, BodySegmentId.FOREARM_L, WRIST_PIVOT, HAND),
            BodySegment(BodySegmentId.HAND_R, BodySegmentId.FOREARM_R, WRIST_PIVOT.mirror(), mirrorX(HAND)),
            BodySegment(
                BodySegmentId.THIGH_L, BodySegmentId.PELVIS, HIP_PIVOT, THIGH,
                regions(BodySegmentId.THIGH_L),
            ),
            BodySegment(
                BodySegmentId.THIGH_R, BodySegmentId.PELVIS, HIP_PIVOT.mirror(), mirrorX(THIGH),
                mirroredRegions(BodySegmentId.THIGH_L),
            ),
            BodySegment(
                BodySegmentId.SHANK_L, BodySegmentId.THIGH_L, KNEE_PIVOT, SHANK,
                regions(BodySegmentId.SHANK_L),
            ),
            BodySegment(
                BodySegmentId.SHANK_R, BodySegmentId.THIGH_R, KNEE_PIVOT.mirror(), mirrorX(SHANK),
                mirroredRegions(BodySegmentId.SHANK_L),
            ),
            BodySegment(BodySegmentId.FOOT_L, BodySegmentId.SHANK_L, ANKLE_PIVOT, FOOT),
            BodySegment(BodySegmentId.FOOT_R, BodySegmentId.SHANK_R, ANKLE_PIVOT.mirror(), mirrorX(FOOT)),
        )
    }

    /**
     * The whole skeleton flipped left-to-right: the root about the box's centreline, every child
     * about its own parent's origin. Used for the back view, so `BACK` and `FRONT` share one set of
     * bones and only differ in the muscle regions drawn on them (`bm05`).
     */
    fun mirrored(segments: List<BodySegment>): List<BodySegment> = segments.map { segment ->
        segment.copy(
            pivot = if (segment.parent == null) {
                BodyPoint(MusclePaths.WIDTH - segment.pivot.x, segment.pivot.y)
            } else {
                segment.pivot.mirror()
            },
            outline = mirrorX(segment.outline),
            muscles = segment.muscles.mapValues { (_, polygons) -> polygons.map(::mirrorX) },
        )
    }

    private fun BodyPoint.mirror() = BodyPoint(-x, y)
}
