package com.myhealth.ui.common.body

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.strength.AnimationClip
import com.myhealth.domain.engine.strength.Keyframe
import com.myhealth.domain.model.BodyFace
import com.myhealth.domain.model.BodyPose
import com.myhealth.domain.model.BodySegmentId
import com.myhealth.domain.engine.strength.AnimationClips
import org.junit.Test

/** P18.2's rendering-timing math (`AnimatedPose.kt`): `anui01`...`anui03`. */
class AnimatedPoseTest {

    @Test
    fun anui01_interpolation_midway_is_the_mean() {
        val start = BodyPose(angles = mapOf(BodySegmentId.THIGH_L to 0f), rootOffsetY = 0f)
        val end = BodyPose(angles = mapOf(BodySegmentId.THIGH_L to 40f), rootOffsetY = 10f)
        val clip = AnimationClip(
            id = "TEST_TWO_KEYFRAME",
            face = BodyFace.SIDE,
            keyframes = listOf(Keyframe(start, holdMs = 0), Keyframe(end, holdMs = 0)),
            transitionMs = 1000,
        )

        val midway = poseAt(clip, elapsedMs = 500)

        assertThat(midway.angleOf(BodySegmentId.THIGH_L)).isWithin(0.01f).of(20f)
        assertThat(midway.rootOffsetY).isWithin(0.01f).of(5f)
    }

    @Test
    fun anui02_reduced_motion_returns_midpoint_pose() {
        val clip = threeKeyframeClip()

        assertThat(midpointPose(clip)).isEqualTo(poseAt(clip, clipDurationMs(clip) / 2L))
    }

    @Test
    fun anui03_loop_wraps_to_first_keyframe() {
        val clip = threeKeyframeClip()

        assertThat(poseAt(clip, clipDurationMs(clip).toLong())).isEqualTo(poseAt(clip, 0L))
    }

    private fun threeKeyframeClip(): AnimationClip = AnimationClip(
        id = "TEST_THREE_KEYFRAME",
        face = BodyFace.SIDE,
        keyframes = listOf(
            Keyframe(BodyPose(angles = mapOf(BodySegmentId.THIGH_L to 0f)), holdMs = 200),
            Keyframe(BodyPose(angles = mapOf(BodySegmentId.THIGH_L to -30f)), holdMs = 300),
            Keyframe(BodyPose(angles = mapOf(BodySegmentId.THIGH_L to 10f)), holdMs = 150),
        ),
        transitionMs = 400,
    )

    @Test
    fun anui04_viewport_contains_every_keyframe_silhouette() {
        AnimationClips.ALL.forEach { clip ->
            val vp = clipViewport(clip)
            clip.keyframes.forEach { keyframe ->
                BodySkeleton.outlinePolygons(clip.face, keyframe.pose).forEach { polygon ->
                    polygon.forEach { point ->
                        assertThat(point.x).isAtLeast(vp.left)
                        assertThat(point.x).isAtMost(vp.left + vp.width)
                        assertThat(point.y).isAtLeast(vp.top)
                        assertThat(point.y).isAtMost(vp.top + vp.height)
                    }
                }
            }
        }
    }

    @Test
    fun anui05_horizontal_clip_viewport_is_landscape() {
        val pushUp = AnimationClips.ALL.first { it.id == "PUSH_UP" }
        val vp = clipViewport(pushUp)
        assertThat(vp.width).isGreaterThan(vp.height)
        val squat = AnimationClips.ALL.first { it.id == "SQUAT" }
        assertThat(clipViewport(squat).height).isGreaterThan(clipViewport(squat).width)
    }

    @Test
    fun anui06_joint_angles_take_the_shortest_arc_through_the_wrap() {
        // 0.7.1: -240° → 0° continues the circle by -120°, it does not unwind +240°.
        val a = BodyPose(angles = mapOf(BodySegmentId.UPPER_ARM_R to -240f))
        val b = BodyPose(angles = mapOf(BodySegmentId.UPPER_ARM_R to 0f))
        assertThat(a.lerp(b, 0.5f).angleOf(BodySegmentId.UPPER_ARM_R)).isWithin(0.01f).of(-300f)
        // A plain small step is still linear, and the root rotation never takes the short cut.
        val c = BodyPose(angles = mapOf(BodySegmentId.SHANK_L to 20f), rootAngle = -170f)
        val d = BodyPose(angles = mapOf(BodySegmentId.SHANK_L to 60f), rootAngle = 170f)
        assertThat(c.lerp(d, 0.25f).angleOf(BodySegmentId.SHANK_L)).isWithin(0.01f).of(30f)
        assertThat(c.lerp(d, 0.5f).rootAngle).isWithin(0.01f).of(0f)
    }
}
