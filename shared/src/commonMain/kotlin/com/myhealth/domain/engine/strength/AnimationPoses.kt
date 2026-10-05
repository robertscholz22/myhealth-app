package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.BodyFace
import com.myhealth.domain.model.BodyPose
import com.myhealth.domain.model.BodySegmentId

/**
 * The clip vocabulary of P18: an [AnimationClip] is a short list of [Keyframe] poses the renderer
 * (P18.2) eases between and then loops back to the first, and this file is the little DSL the
 * seventy-six clips in `AnimationClips` are written in — named joints instead of a raw
 * `Map<BodySegmentId, Float>`, which is what keeps a keyframe one readable line.
 *
 * **Sign conventions** (degrees, positive is clockwise on screen, as `BodyModelTest.bm02` pinned):
 * in the `SIDE` view the subject faces **+x**, so `trunk` (the pose's root rotation about the hips)
 * is positive leaning *forward*, `knee` positive is knee flexion, `ankle` positive is plantar
 * flexion, `hip` **negative** is hip flexion (the thigh swinging forward) and `elbow` negative is
 * elbow flexion (the hand coming up in front). `trunk = +90` lies the figure prone with the head to
 * the right, `-90` supine with the head to the left. `pelvis` counter-rotates the lower body under
 * a leaning trunk: `trunk = 44, pelvis = -44` is a hip hinge with the legs left standing upright.
 */

/** One posture in a clip, held [holdMs] milliseconds before the transition to the next starts. */
data class Keyframe(val pose: BodyPose, val holdMs: Int)

/**
 * One looping exercise animation. [keyframes] runs start → end (→ start, by wrapping), [face] is
 * the silhouette it is legible in, [transitionMs] the ease between two neighbouring keyframes, and
 * [mirror] marks a clip whose pose is one-sided, so the renderer may show the other side with
 * [mirrored].
 */
data class AnimationClip(
    val id: String,
    val face: BodyFace,
    val keyframes: List<Keyframe>,
    val transitionMs: Int,
    val mirror: Boolean = false,
) {
    /** The whole clip's wall-clock length: every hold plus one transition per hop, including the wrap. */
    val durationMs: Int get() = keyframes.sumOf { it.holdMs } + transitionMs * keyframes.size
}

/**
 * The same clip on the other side. In `FRONT` / `BACK` that is a true reflection about the box's
 * centreline — left and right swap **and** every angle, the root offset and the root rotation
 * negate. In `SIDE` the bones themselves are asymmetric (the figure faces +x and cannot be turned
 * round), so mirroring only swaps the near and far limbs: the other leg steps forward. `an08`.
 */
fun AnimationClip.mirrored(): AnimationClip = copy(
    keyframes = keyframes.map { it.copy(pose = it.pose.mirrored(reflect = face != BodyFace.SIDE)) },
)

/** [AnimationClip.mirrored] for one pose; [reflect] additionally negates (a frontal-plane mirror). */
fun BodyPose.mirrored(reflect: Boolean): BodyPose {
    val sign = if (reflect) -1f else 1f
    val swapped = angles.entries.associate { (id, value) -> SIDE_SWAP[id]!! to value * sign }
    return BodyPose(
        angles = swapped,
        rootOffsetX = if (reflect) -rootOffsetX else rootOffsetX,
        rootOffsetY = rootOffsetY,
        rootAngle = if (reflect) -rootAngle else rootAngle,
        rootScale = rootScale,
    )
}

private val SIDE_SWAP: Map<BodySegmentId, BodySegmentId> = BodySegmentId.entries.associateWith { id ->
    when (id) {
        BodySegmentId.UPPER_ARM_L -> BodySegmentId.UPPER_ARM_R
        BodySegmentId.UPPER_ARM_R -> BodySegmentId.UPPER_ARM_L
        BodySegmentId.FOREARM_L -> BodySegmentId.FOREARM_R
        BodySegmentId.FOREARM_R -> BodySegmentId.FOREARM_L
        BodySegmentId.HAND_L -> BodySegmentId.HAND_R
        BodySegmentId.HAND_R -> BodySegmentId.HAND_L
        BodySegmentId.THIGH_L -> BodySegmentId.THIGH_R
        BodySegmentId.THIGH_R -> BodySegmentId.THIGH_L
        BodySegmentId.SHANK_L -> BodySegmentId.SHANK_R
        BodySegmentId.SHANK_R -> BodySegmentId.SHANK_L
        BodySegmentId.FOOT_L -> BodySegmentId.FOOT_R
        BodySegmentId.FOOT_R -> BodySegmentId.FOOT_L
        else -> id
    }
}

/** How long a family of clips holds and eases; the last keyframe gets [endHoldMs]. */
internal data class Timing(val transitionMs: Int, val holdMs: Int, val endHoldMs: Int = holdMs)

/** A working lift: a brief pause at the top, a longer one at the working end. */
internal val LIFT = Timing(transitionMs = 900, holdMs = 220, endHoldMs = 340)

/** Explosive work — a jump, a swing. */
internal val FAST = Timing(transitionMs = 520, holdMs = 140, endHoldMs = 220)

/** A continuous drill with no working end: a carry, a circle, a cat-cow. */
internal val FLOW = Timing(transitionMs = 1100, holdMs = 320)

/** An isometric: two near-identical keyframes so the figure breathes rather than freezes. */
internal val HOLD = Timing(transitionMs = 700, holdMs = 1500)

/** A stretch: settle in slowly, then hold the end position. */
internal val STRETCH = Timing(transitionMs = 1300, holdMs = 500, endHoldMs = 2400)

/**
 * A continuous circle (shoulder CARs): three keyframes 120° apart with next to no hold, closed
 * through the wrap by the shortest-arc angle interpolation of `BodyPose.lerp`.
 */
internal val CIRCLE = Timing(transitionMs = 900, holdMs = 60)

internal fun clip(
    id: String,
    face: BodyFace,
    timing: Timing,
    vararg poses: BodyPose,
    mirror: Boolean = false,
): AnimationClip = AnimationClip(
    id = id,
    face = face,
    keyframes = poses.mapIndexed { index, pose ->
        Keyframe(pose, if (index == poses.lastIndex) timing.endHoldMs else timing.holdMs)
    },
    transitionMs = timing.transitionMs,
    mirror = mirror,
)

/** A profile pose: the unsuffixed limb is the **near** (the subject's right) one; `*Far` is the other. */
@Suppress("LongParameterList")
internal fun sidePose(
    trunk: Float = 0f,
    torso: Float = 0f,
    pelvis: Float = 0f,
    hip: Float = 0f,
    knee: Float = 0f,
    ankle: Float = 0f,
    hipFar: Float = hip,
    kneeFar: Float = knee,
    ankleFar: Float = ankle,
    shoulder: Float = 0f,
    elbow: Float = 0f,
    wrist: Float = 0f,
    shoulderFar: Float = shoulder,
    elbowFar: Float = elbow,
    wristFar: Float = wrist,
    neck: Float = 0f,
    head: Float = 0f,
    dx: Float = 0f,
    dy: Float = 0f,
    scale: Float = 1f,
): BodyPose = build(
    trunk, torso, pelvis, neck, head,
    hipFar, kneeFar, ankleFar, hip, knee, ankle,
    shoulderFar, elbowFar, wristFar, shoulder, elbow, wrist,
    dx, dy, scale,
)

/** A frontal pose: the unsuffixed limb is the subject's **left**; the right mirrors it by default. */
@Suppress("LongParameterList")
internal fun frontPose(
    trunk: Float = 0f,
    torso: Float = 0f,
    pelvis: Float = 0f,
    hip: Float = 0f,
    knee: Float = 0f,
    ankle: Float = 0f,
    hipRight: Float = -hip,
    kneeRight: Float = -knee,
    ankleRight: Float = -ankle,
    shoulder: Float = 0f,
    elbow: Float = 0f,
    wrist: Float = 0f,
    shoulderRight: Float = -shoulder,
    elbowRight: Float = -elbow,
    wristRight: Float = -wrist,
    neck: Float = 0f,
    head: Float = 0f,
    dx: Float = 0f,
    dy: Float = 0f,
    scale: Float = 1f,
): BodyPose = build(
    trunk, torso, pelvis, neck, head,
    hip, knee, ankle, hipRight, kneeRight, ankleRight,
    shoulder, elbow, wrist, shoulderRight, elbowRight, wristRight,
    dx, dy, scale,
)

@Suppress("LongParameterList")
private fun build(
    trunk: Float, torso: Float, pelvis: Float, neck: Float, head: Float,
    hipL: Float, kneeL: Float, ankleL: Float, hipR: Float, kneeR: Float, ankleR: Float,
    shoulderL: Float, elbowL: Float, wristL: Float, shoulderR: Float, elbowR: Float, wristR: Float,
    dx: Float, dy: Float, scale: Float,
): BodyPose = BodyPose(
    angles = mapOf(
        BodySegmentId.TORSO to torso,
        BodySegmentId.NECK to neck,
        BodySegmentId.HEAD to head,
        BodySegmentId.PELVIS to pelvis,
        BodySegmentId.THIGH_L to hipL,
        BodySegmentId.SHANK_L to kneeL,
        BodySegmentId.FOOT_L to ankleL,
        BodySegmentId.THIGH_R to hipR,
        BodySegmentId.SHANK_R to kneeR,
        BodySegmentId.FOOT_R to ankleR,
        BodySegmentId.UPPER_ARM_L to shoulderL,
        BodySegmentId.FOREARM_L to elbowL,
        BodySegmentId.HAND_L to wristL,
        BodySegmentId.UPPER_ARM_R to shoulderR,
        BodySegmentId.FOREARM_R to elbowR,
        BodySegmentId.HAND_R to wristR,
    ),
    rootOffsetX = dx,
    rootOffsetY = dy,
    rootAngle = trunk,
    rootScale = scale,
)
