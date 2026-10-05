package com.myhealth.domain.model

/** Mirrors the `body_measurement` table (PLAN §2.2.1). */
data class BodyMeasurement(
    val id: Long = 0,
    val measuredAtMillis: Long,
    val day: Long,
    val weightKg: Double?,
    val bodyFatPercent: Double?,
    val muscleMassKg: Double?,
    val boneMassKg: Double?,
    val bodyWaterPercent: Double?,
    val source: ActivitySource,
    val externalId: String? = null,
    val note: String? = null,
)

// ---- the body figure's pose vocabulary (P18.1) -----------------------------------------------
//
// P15.1 kept these three types next to the geometry in `ui/common/body`, which was fine while the
// only pose was "standing"; the exercise animations of P18 are authored in
// `domain/engine/strength` (`AnimationClips`, `ExerciseAnimations`), and the domain may not import
// the UI layer (R6 / `ArchitectureTest`), so the vocabulary moves down here and the geometry —
// outlines, muscle regions, the forward kinematics — stays up there. Pure data: a face, a map of
// angles and the four root numbers; nothing here knows what a polygon is.

/** The sixteen parts the figure is built from; `_L` / `_R` are the *subject's* left and right. */
enum class BodySegmentId {
    HEAD,
    NECK,
    TORSO,
    PELVIS,
    UPPER_ARM_L,
    UPPER_ARM_R,
    FOREARM_L,
    FOREARM_R,
    HAND_L,
    HAND_R,
    THIGH_L,
    THIGH_R,
    SHANK_L,
    SHANK_R,
    FOOT_L,
    FOOT_R,
}

/**
 * Which silhouette a skeleton draws. `FRONT` and `BACK` share one set of bones and differ only in
 * the muscle regions painted on them; `SIDE` (P18.1) is a second set of bones — a profile facing
 * the **+x** direction (to the viewer's right) — carrying the groups that are visible edge-on.
 *
 * Frontal-plane movements (lateral raise, overhead press, pull-up, thoracic rotation) animate in
 * `FRONT` or `BACK`; sagittal ones (squat, hinge, lunge, row, push-up, plank, curl, calf raise and
 * nearly every stretch) animate in `SIDE`.
 */
enum class BodyFace { FRONT, BACK, SIDE }

/**
 * One posture of the figure.
 *
 * [angles] is a rotation in **degrees** per segment about that segment's own pivot, composed down
 * the parent chain; a missing segment is `0f`. Positive is **clockwise on screen** (y grows
 * downwards) — the convention `BodyModelTest.bm02` pinned in P15.1. In the `SIDE` view, where the
 * subject faces +x, that reads as: knee flexion, trunk flexion and plantar flexion positive, hip
 * flexion (the thigh swinging forward) negative.
 *
 * The root numbers move the *whole* figure after the joint chain has been composed, about a
 * fixed pivot — the standing figure's hip joint, the centre of the 100 × 220 box at `y = 110`:
 *
 * - [rootAngle] rotates it there (so a hinge is "lean the trunk over the hips", `+90` lies the
 *   figure down **prone**, head to the right, and `-90` lies it down **supine**, head to the left);
 * - [rootScale] shrinks it about the same point — the figure is 213 units long and cannot lie
 *   horizontally inside a 100-unit-wide box at full size, so the horizontal clips carry ≈ 0.4;
 *   one scale for a whole clip, so the figure does not pulse between keyframes;
 * - [rootOffsetX] / [rootOffsetY] then translate it, per keyframe, to keep the contact point
 *   still: folding the knees lifts the *feet* (they are the leaves of the chain), so a squat is
 *   "bend the joints, then push the figure back down until the soles are on the floor again".
 *
 * The order is scale → rotate → translate, all in the normalised box.
 */
data class BodyPose(
    val angles: Map<BodySegmentId, Float> = emptyMap(),
    val rootOffsetX: Float = 0f,
    val rootOffsetY: Float = 0f,
    val rootAngle: Float = 0f,
    val rootScale: Float = 1f,
) {

    fun angleOf(id: BodySegmentId): Float = angles[id] ?: 0f

    /**
     * This pose blended [t] of the way towards [other] (`0` is `this`, `1` is [other]) — every
     * angle, the root offset, the root rotation and the root scale linearly. A segment either pose
     * leaves out counts as `0f`, so interpolating towards [STANDING] straightens the figure out
     * instead of dropping joints. [t] is clamped, which is what makes an eased 0…1 progress safe.
     *
     * Joint angles take the **shortest arc** (0.7.1): `-240° → 0°` turns a further −120° rather
     * than unwinding +240°, which is what lets a three-keyframe circle (shoulder CARs) close
     * through the wrap. No clip moves a joint more than 180° between two neighbouring keyframes,
     * so every other transition is unchanged. The root rotation stays linear — a trunk never turns
     * that far, and a hinge must not pick the other way round.
     */
    fun lerp(other: BodyPose, t: Float): BodyPose {
        val f = t.coerceIn(0f, 1f)
        if (f == 0f) return this
        if (f == 1f) return other
        val ids = angles.keys + other.angles.keys
        return BodyPose(
            angles = ids.associateWith { id -> mixAngle(angleOf(id), other.angleOf(id), f) },
            rootOffsetX = mix(rootOffsetX, other.rootOffsetX, f),
            rootOffsetY = mix(rootOffsetY, other.rootOffsetY, f),
            rootAngle = mix(rootAngle, other.rootAngle, f),
            rootScale = mix(rootScale, other.rootScale, f),
        )
    }

    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun mixAngle(a: Float, b: Float, t: Float): Float {
        val delta = ((b - a + 180f).mod(360f)) - 180f
        return a + delta * t
    }

    companion object {
        /** Every joint at zero: the anatomical standing figure the geometry is drawn from. */
        val STANDING: BodyPose = BodyPose()

        /** The point [rootAngle] and [rootScale] act about: the standing figure's hip joint. */
        const val ROOT_PIVOT_X: Float = 50f
        const val ROOT_PIVOT_Y: Float = 110f
    }
}
