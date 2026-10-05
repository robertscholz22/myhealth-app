package com.myhealth.domain.engine.strength

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.myhealth.domain.model.BodyFace
import com.myhealth.domain.model.BodyPose
import com.myhealth.domain.model.BodySegmentId
import org.junit.Test
import kotlin.math.abs

/** P18.1's `an02` / `an08`: the shape of a clip and what mirroring one means. */
class AnimationClipsTest {

    @Test
    fun an02_every_clip_has_2_to_4_keyframes_and_positive_timing() {
        assertThat(AnimationClips.ALL).isNotEmpty()
        assertThat(AnimationClips.ALL.map { it.id }.toSet()).hasSize(AnimationClips.ALL.size)

        AnimationClips.ALL.forEach { clip ->
            val where = assertWithMessage(clip.id)
            where.that(clip.keyframes.size).isAtLeast(2)
            where.that(clip.keyframes.size).isAtMost(4)
            where.that(clip.transitionMs).isGreaterThan(0)
            where.that(clip.id).isNotEmpty()
            clip.keyframes.forEach { frame ->
                assertWithMessage("${clip.id} hold").that(frame.holdMs).isGreaterThan(0)
                // A clip that is only shrunk out of existence would pass the box test and show nothing.
                assertWithMessage("${clip.id} scale").that(frame.pose.rootScale).isGreaterThan(0.3f)
                assertWithMessage("${clip.id} scale").that(frame.pose.rootScale).isAtMost(1f)
                // The whole clip must keep one scale: a per-keyframe scale would make the figure pulse.
                assertWithMessage("${clip.id} scale is constant")
                    .that(frame.pose.rootScale)
                    .isEqualTo(clip.keyframes.first().pose.rootScale)
                assertWithMessage("${clip.id} angles")
                    .that(frame.pose.angles.keys)
                    .containsExactlyElementsIn(BodySegmentId.entries)
            }
            // A cyclic clip moves: two keyframes that are identical are only allowed for a hold.
            where.that(clip.durationMs).isGreaterThan(clip.transitionMs)
            // 0.7.1: joint angles interpolate along the shortest arc, so no clip may ask a joint
            // to travel 180° or more between neighbours — it would go the other way round. The
            // one clip that wants exactly that is the shoulder circle, whose wrap (-240° → 0°)
            // is meant to continue the circle by -120°.
            if (clip.id == "SHOULDER_CARS") return@forEach
            clip.keyframes.forEachIndexed { index, frame ->
                val next = clip.keyframes[(index + 1) % clip.keyframes.size]
                BodySegmentId.entries.forEach { id ->
                    assertWithMessage("${clip.id} keyframe $index → next, $id")
                        .that(abs(next.pose.angleOf(id) - frame.pose.angleOf(id)))
                        .isLessThan(180f)
                }
            }
        }

        // The three faces are all in use; the profile carries the sagittal bulk of the catalog.
        val faces = AnimationClips.ALL.groupingBy { it.face }.eachCount()
        assertThat(faces.keys).containsExactlyElementsIn(BodyFace.entries)
        assertThat(faces.getValue(BodyFace.SIDE)).isGreaterThan(AnimationClips.ALL.size / 2)

        // Spot check the two timings that differ most: a lift reps, a stretch holds.
        assertThat(AnimationClips.SQUAT.keyframes.last().holdMs).isEqualTo(340)
        assertThat(AnimationClips.COUCH_STRETCH.keyframes.last().holdMs).isEqualTo(2400)
    }

    @Test
    fun an08_mirror_clips_flip_left_right() {
        val mirrored = AnimationClips.ALL.filter { it.mirror }
        assertThat(mirrored).isNotEmpty()
        // A clip only claims `mirror` if it actually has a side: a symmetric pose would be a lie.
        mirrored.forEach { clip ->
            assertWithMessage("${clip.id} is flagged mirror but is symmetric")
                .that(clip.keyframes.any { asymmetric(it.pose) })
                .isTrue()
        }

        // A profile clip swaps the near and far limbs — the other leg steps forward, same facing.
        val lunge = AnimationClips.LUNGE
        assertThat(lunge.face).isEqualTo(BodyFace.SIDE)
        val bottom = lunge.keyframes.last().pose
        val flippedBottom = lunge.mirrored().keyframes.last().pose
        assertThat(flippedBottom.angleOf(BodySegmentId.THIGH_L))
            .isEqualTo(bottom.angleOf(BodySegmentId.THIGH_R))
        assertThat(flippedBottom.angleOf(BodySegmentId.THIGH_R))
            .isEqualTo(bottom.angleOf(BodySegmentId.THIGH_L))
        assertThat(flippedBottom.rootAngle).isEqualTo(bottom.rootAngle)
        assertThat(flippedBottom.rootOffsetX).isEqualTo(bottom.rootOffsetX)

        // A frontal clip is a true reflection: sides swap *and* every angle and the root x negate.
        val abduction = AnimationClips.HIP_ABDUCTION
        assertThat(abduction.face).isEqualTo(BodyFace.FRONT)
        val out = abduction.keyframes.last().pose
        val flippedOut = abduction.mirrored().keyframes.last().pose
        assertThat(flippedOut.angleOf(BodySegmentId.THIGH_L))
            .isEqualTo(-out.angleOf(BodySegmentId.THIGH_R))
        assertThat(flippedOut.rootOffsetX).isEqualTo(-out.rootOffsetX)
        assertThat(flippedOut.rootAngle).isEqualTo(-out.rootAngle)
        assertThat(flippedOut.rootOffsetY).isEqualTo(out.rootOffsetY)

        // Mirroring twice is the identity, for every flagged clip and both faces.
        mirrored.forEach { clip ->
            assertWithMessage(clip.id).that(clip.mirrored().mirrored()).isEqualTo(clip)
            assertWithMessage("${clip.id} is unchanged by mirroring")
                .that(clip.mirrored())
                .isNotEqualTo(clip)
        }
    }

    private fun asymmetric(pose: BodyPose): Boolean = PAIRS.any { (left, right) ->
        abs(pose.angleOf(left) - pose.angleOf(right)) > 0.01f
    }

    private companion object {
        val PAIRS = listOf(
            BodySegmentId.THIGH_L to BodySegmentId.THIGH_R,
            BodySegmentId.SHANK_L to BodySegmentId.SHANK_R,
            BodySegmentId.FOOT_L to BodySegmentId.FOOT_R,
            BodySegmentId.UPPER_ARM_L to BodySegmentId.UPPER_ARM_R,
            BodySegmentId.FOREARM_L to BodySegmentId.FOREARM_R,
            BodySegmentId.HAND_L to BodySegmentId.HAND_R,
        )
    }
}
