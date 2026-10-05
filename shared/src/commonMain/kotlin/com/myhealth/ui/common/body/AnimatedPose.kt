package com.myhealth.ui.common.body

import com.myhealth.domain.engine.strength.AnimationClip
import com.myhealth.domain.model.BodyPose

/**
 * The pure timing math behind [AnimatedBodyFigure] (P18.2): no Compose types, so it is a plain JVM
 * unit test (`AnimatedPoseTest`) rather than an instrumented one — same split as [MusclePaths] vs
 * [BodyFigure].
 *
 * A clip is a loop: [clipDurationMs] (== [AnimationClip.durationMs]) is one full cycle, and
 * [poseAt] walks it as alternating **hold** segments (steady at one keyframe) and **transition**
 * segments (eased towards the next, [AnimationClip.transitionMs] long, including the wrap from the
 * last keyframe back to the first) — exactly the segments [AnimationClip.durationMs] sums.
 */

/** One full cycle of [clip], in milliseconds — `AnimationClip.durationMs` under its own name here
 * so callers of this file need not reach into the domain type for it. */
fun clipDurationMs(clip: AnimationClip): Int = clip.durationMs

/**
 * The clip's pose at [elapsedMs] since the loop started. Negative or overlong [elapsedMs] wraps
 * (`elapsed mod duration`, `anui03`); a transition eases with [smoothstep] rather than linearly, so
 * the figure settles into and out of every hold instead of snapping.
 *
 * [BodyPose.lerp] operates on whatever poses [clip] already carries — a caller who wants the other
 * side of a `mirror` clip calls `AnimationClip.mirrored()` (P18.1) before handing it here; this
 * function does not do that on its own; see the mirror note in the P18.2 report.
 */
fun poseAt(clip: AnimationClip, elapsedMs: Long): BodyPose {
    val frames = clip.keyframes
    val duration = clipDurationMs(clip)
    if (frames.isEmpty() || duration <= 0) return BodyPose.STANDING

    var t = elapsedMs % duration
    if (t < 0) t += duration

    frames.forEachIndexed { index, keyframe ->
        if (t < keyframe.holdMs) return keyframe.pose
        t -= keyframe.holdMs

        val next = frames[(index + 1) % frames.size]
        if (t < clip.transitionMs) {
            val progress = if (clip.transitionMs == 0) 1f else t / clip.transitionMs.toFloat()
            return keyframe.pose.lerp(next.pose, smoothstep(progress))
        }
        t -= clip.transitionMs
    }
    // Floating-point rounding can leave a sliver of `t` unconsumed at the very end of the last
    // transition; that sliver is the loop point, i.e. the first keyframe.
    return frames.first().pose
}

/** The pose halfway through [clip]'s loop — the still frame reduced motion and thumbnails use. */
fun midpointPose(clip: AnimationClip): BodyPose = poseAt(clip, clipDurationMs(clip) / 2L)

/** Smoothstep ease-in-out: flat tangents at both ends, `0.5` maps to `0.5` exactly. */
private fun smoothstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/**
 * POLISH-21: the smallest window onto the body box that contains the clip's silhouette in every
 * phase of the movement (the keyframes and [VIEWPORT_SAMPLES] evenly spaced in-between poses),
 * padded by [VIEWPORT_PADDING] on each side. Computed once per clip; a fixed window keeps the
 * figure from swimming while it moves.
 */
fun clipViewport(clip: AnimationClip): BodyViewport {
    val duration = clipDurationMs(clip).coerceAtLeast(1)
    val poses = clip.keyframes.map { it.pose } +
        (0 until VIEWPORT_SAMPLES).map { i -> poseAt(clip, duration.toLong() * i / VIEWPORT_SAMPLES) }
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    poses.forEach { pose ->
        BodySkeleton.outlinePolygons(clip.face, pose).forEach { polygon ->
            polygon.forEach { point ->
                if (point.x < minX) minX = point.x
                if (point.x > maxX) maxX = point.x
                if (point.y < minY) minY = point.y
                if (point.y > maxY) maxY = point.y
            }
        }
    }
    if (minX > maxX || minY > maxY) return BodyViewport.FULL
    val padX = (maxX - minX) * VIEWPORT_PADDING
    val padY = (maxY - minY) * VIEWPORT_PADDING
    return BodyViewport(
        left = minX - padX,
        top = minY - padY,
        width = (maxX - minX) + 2 * padX,
        height = (maxY - minY) + 2 * padY,
    )
}

private const val VIEWPORT_SAMPLES = 12
private const val VIEWPORT_PADDING = 0.08f
