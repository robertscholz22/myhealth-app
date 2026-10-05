package com.myhealth.ui.common.body

import com.myhealth.domain.model.BodySegmentId
import com.myhealth.ui.common.body.BodyShapes.ellipse
import com.myhealth.ui.common.body.BodyShapes.smoothClosed

/**
 * The **profile** skeleton (P18.1): the same sixteen [BodySegmentId]s, the same 100 x 220 box and
 * the same joint heights as `BodyGeometry`, but seen edge-on with the subject **facing +x** (to
 * the viewer's right). Sagittal movements — squat, hinge, lunge, row, push-up, plank, curl, calf
 * raise and nearly every stretch — are only legible from here.
 *
 * A profile is not left/right symmetric, so it cannot reuse `BodyGeometry`'s mirroring: the near
 * limbs are the subject's **right** (facing +x with screen-up meaning the right side is towards
 * the viewer) and are drawn last, over the far left limbs, which sit a little further back in x
 * and are otherwise identical. `BodyFigure` unions the outlines, so the far limb reads as the
 * hint of a second arm/leg behind the near one rather than as a separate figure.
 *
 * Joint heights are shared with the front view so a clip can be authored against either face:
 * shoulders y = 36, hips y = 110, knees y = 172, ankles y = 208, soles y = 217, head top y = 3.5.
 */
internal object BodyProfile {

    // ---- joints -------------------------------------------------------------------------------

    private val TORSO_PIVOT = BodyPoint(50f, 36f) // the shoulder joint, seen edge-on
    private val NECK_PIVOT = BodyPoint(-1f, -2f)
    private val HEAD_PIVOT = BodyPoint(0.5f, -4f)
    private val SHOULDER_NEAR = BodyPoint(1.5f, 5f)
    private val SHOULDER_FAR = BodyPoint(-2.5f, 5f)
    private val ELBOW_PIVOT = BodyPoint(0f, 39.5f)
    private val WRIST_PIVOT = BodyPoint(0f, 34f)
    private val PELVIS_PIVOT = BodyPoint(0f, 74f)
    private val HIP_NEAR = BodyPoint(1.5f, 16f)
    private val HIP_FAR = BodyPoint(-3f, 16f)
    private val KNEE_PIVOT = BodyPoint(0f, 46f)
    private val ANKLE_PIVOT = BodyPoint(0f, 36f)

    // ---- outlines (local frames, pivot at the origin) --------------------------------------------

    /** Rounded upper back, chest forward at y ≈ 20, belly, then the hip bowl. */
    private val TORSO = smoothClosed(
        listOf(
            BodyPoint(-10f, -2f), BodyPoint(-13f, 6f), BodyPoint(-14f, 18f), BodyPoint(-12.5f, 32f),
            BodyPoint(-10.5f, 48f), BodyPoint(-11f, 62f), BodyPoint(-10f, 74f), BodyPoint(-6f, 80f),
            BodyPoint(2f, 81f), BodyPoint(8f, 78f), BodyPoint(10.5f, 66f), BodyPoint(9f, 52f),
            BodyPoint(10f, 38f), BodyPoint(13f, 22f), BodyPoint(13.5f, 8f), BodyPoint(10f, -1f),
            BodyPoint(0f, -3f),
        ),
    )

    private val NECK = smoothClosed(
        listOf(
            BodyPoint(-5f, 2f), BodyPoint(-4.6f, -2f), BodyPoint(-4.2f, -6f),
            BodyPoint(4.2f, -6f), BodyPoint(4.6f, -2f), BodyPoint(5f, 2f),
        ),
        steps = 4,
    )

    /** Skull at −x, brow / nose / chin stepping forward at +x. */
    private val HEAD = smoothClosed(
        listOf(
            BodyPoint(0f, -26.5f), BodyPoint(7.5f, -24f), BodyPoint(11f, -17f), BodyPoint(11.5f, -9f),
            BodyPoint(12.8f, -5f), BodyPoint(10f, -2f), BodyPoint(9f, 1.5f), BodyPoint(4f, 3.2f),
            BodyPoint(0f, 3.4f), BodyPoint(-5f, 2.6f), BodyPoint(-9f, -0.5f), BodyPoint(-11.5f, -8f),
            BodyPoint(-11f, -18f), BodyPoint(-7.5f, -24.5f),
        ),
    )

    /** Hanging straight down beside the ribs: a tapered tube, deltoid cap at the top. */
    private val UPPER_ARM = smoothClosed(
        listOf(
            BodyPoint(-5.5f, 2f), BodyPoint(-6.5f, 12f), BodyPoint(-6f, 24f), BodyPoint(-5f, 36f),
            BodyPoint(-4f, 42.5f), BodyPoint(0f, 43.5f), BodyPoint(2.5f, 36f), BodyPoint(4f, 24f),
            BodyPoint(5f, 12f), BodyPoint(5.5f, 2f), BodyPoint(4.5f, -1.5f), BodyPoint(0f, -3f),
        ),
    )

    private val FOREARM = smoothClosed(
        listOf(
            BodyPoint(-4f, -4f), BodyPoint(-5f, 6f), BodyPoint(-4.5f, 18f), BodyPoint(-3.5f, 28f),
            BodyPoint(-2.5f, 35f), BodyPoint(0f, 36.5f), BodyPoint(2f, 28f), BodyPoint(3f, 16f),
            BodyPoint(3.5f, 4f), BodyPoint(3.5f, -4f),
        ),
    )

    private val HAND = ellipse(cx = 0f, cy = 4.2f, rx = 3.6f, ry = 9.2f, steps = 20)

    /** The pelvis bowl, with the glute shelf bulging behind at −x. */
    private val PELVIS = smoothClosed(
        listOf(
            BodyPoint(-9f, -7f), BodyPoint(-12f, 0f), BodyPoint(-13.5f, 9f), BodyPoint(-12f, 18f),
            BodyPoint(-6f, 25f), BodyPoint(2f, 26f), BodyPoint(8f, 22f), BodyPoint(10.5f, 13f),
            BodyPoint(10f, 3f), BodyPoint(7f, -5f),
        ),
    )

    private val THIGH = smoothClosed(
        listOf(
            BodyPoint(-7.5f, -9f), BodyPoint(-9f, 2f), BodyPoint(-9.5f, 16f), BodyPoint(-8.5f, 30f),
            BodyPoint(-7f, 42f), BodyPoint(-4.5f, 49f), BodyPoint(0.5f, 49f), BodyPoint(3.5f, 42f),
            BodyPoint(6f, 28f), BodyPoint(7.5f, 12f), BodyPoint(7.5f, -2f), BodyPoint(5f, -9f),
        ),
    )

    /** Calf belly behind the shin, tapering into the ankle. */
    private val SHANK = smoothClosed(
        listOf(
            BodyPoint(-3.5f, -6f), BodyPoint(-5.5f, 5f), BodyPoint(-6.5f, 14f), BodyPoint(-5f, 24f),
            BodyPoint(-3.5f, 32f), BodyPoint(-2f, 40f), BodyPoint(0.8f, 40f), BodyPoint(2.2f, 30f),
            BodyPoint(3f, 18f), BodyPoint(3.8f, 6f), BodyPoint(3.5f, -6f),
        ),
    )

    /** Heel behind the ankle, toes forward: the only shape the front view has no counterpart for. */
    private val FOOT = smoothClosed(
        listOf(
            BodyPoint(-5.5f, -5f), BodyPoint(-6.5f, 1f), BodyPoint(-5.5f, 6f), BodyPoint(-1.5f, 9f),
            BodyPoint(6f, 9f), BodyPoint(10.5f, 7.5f), BodyPoint(11.5f, 4f), BodyPoint(7.5f, 1.5f),
            BodyPoint(1.5f, -1.5f), BodyPoint(-1f, -5f),
        ),
        steps = 4,
    )

    /** A limb piece pushed [by] units backwards — the far arm and leg, drawn behind the near ones. */
    private fun back(polygon: MusclePaths.Polygon, by: Float = 1.6f): MusclePaths.Polygon =
        polygon.map { BodyPoint(it.x - by, it.y) }

    /** The sixteen profile segments; far (left) limbs first so the near (right) ones draw over them. */
    fun segments(): List<BodySegment> {
        val muscles = BodyProfileMuscles.REGIONS
        fun regions(id: BodySegmentId) = muscles[id].orEmpty()
        return listOf(
            BodySegment(BodySegmentId.TORSO, null, TORSO_PIVOT, TORSO, regions(BodySegmentId.TORSO)),
            BodySegment(BodySegmentId.NECK, BodySegmentId.TORSO, NECK_PIVOT, NECK),
            BodySegment(BodySegmentId.HEAD, BodySegmentId.NECK, HEAD_PIVOT, HEAD),
            BodySegment(
                BodySegmentId.PELVIS, BodySegmentId.TORSO, PELVIS_PIVOT, PELVIS,
                regions(BodySegmentId.PELVIS),
            ),
            BodySegment(BodySegmentId.UPPER_ARM_L, BodySegmentId.TORSO, SHOULDER_FAR, back(UPPER_ARM)),
            BodySegment(BodySegmentId.FOREARM_L, BodySegmentId.UPPER_ARM_L, ELBOW_PIVOT, back(FOREARM)),
            BodySegment(BodySegmentId.HAND_L, BodySegmentId.FOREARM_L, WRIST_PIVOT, back(HAND)),
            BodySegment(BodySegmentId.THIGH_L, BodySegmentId.PELVIS, HIP_FAR, back(THIGH)),
            BodySegment(BodySegmentId.SHANK_L, BodySegmentId.THIGH_L, KNEE_PIVOT, back(SHANK)),
            BodySegment(BodySegmentId.FOOT_L, BodySegmentId.SHANK_L, ANKLE_PIVOT, back(FOOT)),
            BodySegment(
                BodySegmentId.UPPER_ARM_R, BodySegmentId.TORSO, SHOULDER_NEAR, UPPER_ARM,
                regions(BodySegmentId.UPPER_ARM_R),
            ),
            BodySegment(
                BodySegmentId.FOREARM_R, BodySegmentId.UPPER_ARM_R, ELBOW_PIVOT, FOREARM,
                regions(BodySegmentId.FOREARM_R),
            ),
            BodySegment(BodySegmentId.HAND_R, BodySegmentId.FOREARM_R, WRIST_PIVOT, HAND),
            BodySegment(
                BodySegmentId.THIGH_R, BodySegmentId.PELVIS, HIP_NEAR, THIGH,
                regions(BodySegmentId.THIGH_R),
            ),
            BodySegment(
                BodySegmentId.SHANK_R, BodySegmentId.THIGH_R, KNEE_PIVOT, SHANK,
                regions(BodySegmentId.SHANK_R),
            ),
            BodySegment(BodySegmentId.FOOT_R, BodySegmentId.SHANK_R, ANKLE_PIVOT, FOOT),
        )
    }
}
