package com.myhealth.ui.common.body

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.myhealth.domain.engine.strength.AnimationClip
import com.myhealth.domain.engine.strength.AnimationClips
import com.myhealth.domain.model.BodyFace
import com.myhealth.domain.model.BodyPose
import com.myhealth.domain.model.BodySegmentId
import com.myhealth.domain.model.MuscleGroup
import org.junit.Test
import kotlin.math.abs

/**
 * P15.1's `bm01`…`bm05` over the jointed body model behind [MusclePaths], plus P18.1's `an03`…`an07`
 * — the geometry half of the exercise animations, which needs the skeleton and so lives here rather
 * than next to the pure-data clips.
 */
class BodyModelTest {

    @Test
    fun bm01_standing_pose_polygons_are_inside_the_box() {
        BodyFace.entries.forEach { face ->
            val polygons = BodySkeleton.outlinePolygons(face, BodyPose.STANDING) +
                BodySkeleton.worldPolygons(face, BodyPose.STANDING).values.flatten()
            assertThat(polygons).isNotEmpty()
            polygons.flatten().forEach { point ->
                assertThat(point.x >= 0f && point.x <= MusclePaths.WIDTH).isTrue()
                assertThat(point.y >= 0f && point.y <= MusclePaths.HEIGHT).isTrue()
            }
            // The figure fills the box: head near the top, soles near the bottom.
            val ys = polygons.flatten().map { it.y }
            assertThat(ys.min()).isLessThan(10f)
            assertThat(ys.max()).isGreaterThan(205f)
        }
    }

    @Test
    fun bm02_rotating_a_forearm_moves_its_hand_but_not_the_torso() {
        val standing = frames(BodySkeleton.FRONT, BodyPose.STANDING)
        val bent = frames(
            BodySkeleton.FRONT,
            BodyPose(mapOf(BodySegmentId.FOREARM_L to -90f)),
        )
        val origin = BodyPoint(0f, 0f)

        // The hand hangs off the rotated forearm, so it swings with it.
        val handStanding = standing.getValue(BodySegmentId.HAND_L).apply(origin)
        val handBent = bent.getValue(BodySegmentId.HAND_L).apply(origin)
        assertThat(distance(handStanding, handBent)).isGreaterThan(20f)

        // Its parent chain above the elbow is untouched.
        listOf(BodySegmentId.TORSO, BodySegmentId.HEAD, BodySegmentId.UPPER_ARM_L, BodySegmentId.THIGH_R)
            .forEach { id ->
                assertThat(distance(standing.getValue(id).apply(origin), bent.getValue(id).apply(origin)))
                    .isLessThan(0.001f)
            }

        // A −90° rotation about the elbow lifts the wrist to the elbow's own height.
        val elbow = standing.getValue(BodySegmentId.FOREARM_L).apply(origin)
        assertThat(abs(handBent.y - elbow.y)).isLessThan(6f)
        // …and swings it across the body (screen y grows downwards, so −90° is anticlockwise).
        assertThat(handBent.x).isGreaterThan(handStanding.x + 20f)
    }

    @Test
    fun bm03_point_in_polygon_hit_test() {
        val square = listOf(
            BodyPoint(0f, 0f), BodyPoint(10f, 0f), BodyPoint(10f, 10f), BodyPoint(0f, 10f),
        )
        assertThat(pointInPolygon(square, 5f, 5f)).isTrue()
        assertThat(pointInPolygon(square, 11f, 5f)).isFalse()

        // A concave "L": the notch is outside even though it is inside the bounding box.
        val ell = listOf(
            BodyPoint(0f, 0f), BodyPoint(4f, 0f), BodyPoint(4f, 6f),
            BodyPoint(10f, 6f), BodyPoint(10f, 10f), BodyPoint(0f, 10f),
        )
        assertThat(pointInPolygon(ell, 2f, 2f)).isTrue()
        assertThat(pointInPolygon(ell, 8f, 2f)).isFalse()

        // On the real figure: the chest is hit inside the torso and missed beside it.
        val chest = MusclePaths.FRONT.getValue(MuscleGroup.CHEST)
        val inside = chest.first().let { p -> BodyPoint(p.map { it.x }.average().toFloat(), p.map { it.y }.average().toFloat()) }
        assertThat(MusclePaths.groupAt(MusclePaths.FRONT, inside.x, inside.y)).isEqualTo(MuscleGroup.CHEST)
        assertThat(MusclePaths.groupAt(MusclePaths.FRONT, 2f, 2f)).isNull()
        // The gap between the arm and the torso is not claimed by anything (P14.7's bounds test was).
        assertThat(MusclePaths.groupAt(MusclePaths.FRONT, 50f, 210f)).isNull()
    }

    @Test
    fun bm04_muscle_regions_lie_inside_their_segment_outline_bounds() {
        val tolerance = 0.5f
        // P18.1 adds the profile skeleton to what this pins.
        BodyFace.entries.map { BodySkeleton.segmentsOf(it) }.forEach { segments ->
            segments.forEach { segment ->
                if (segment.muscles.isEmpty()) return@forEach
                val outline = segment.outline
                val minX = outline.minOf { it.x } - tolerance
                val maxX = outline.maxOf { it.x } + tolerance
                val minY = outline.minOf { it.y } - tolerance
                val maxY = outline.maxOf { it.y } + tolerance
                segment.muscles.forEach { (group, polygons) ->
                    polygons.flatten().forEach { point ->
                        assertWithMessage("${segment.id} / $group point $point")
                            .that(point.x in minX..maxX && point.y in minY..maxY)
                            .isTrue()
                    }
                }
            }
        }
    }

    @Test
    fun bm05_front_and_back_are_mirrored_consistently() {
        val front = BodySkeleton.FRONT.associateBy { it.id }
        val back = BodySkeleton.BACK.associateBy { it.id }
        assertThat(back.keys).isEqualTo(front.keys)
        assertThat(front.keys).containsExactlyElementsIn(BodySegmentId.entries)

        front.forEach { (id, segment) ->
            val other = back.getValue(id)
            assertThat(other.parent).isEqualTo(segment.parent)
            assertThat(other.pivot.y).isWithin(TOL).of(segment.pivot.y)
            val mirroredX = if (segment.parent == null) {
                MusclePaths.WIDTH - segment.pivot.x
            } else {
                -segment.pivot.x
            }
            assertThat(other.pivot.x).isWithin(TOL).of(mirroredX)
            // The bones are the same shape, just flipped: same bounds width, mirrored x extent.
            assertThat(other.outline.size).isEqualTo(segment.outline.size)
            assertThat(other.outline.maxOf { it.x }).isWithin(TOL).of(-segment.outline.minOf { it.x })
            assertThat(other.outline.minOf { it.x }).isWithin(TOL).of(-segment.outline.maxOf { it.x })
        }

        // Both silhouettes therefore occupy the same place in the box.
        val frontBounds = BodySkeleton.outlinePolygons(BodyFace.FRONT).flatten()
        val backBounds = BodySkeleton.outlinePolygons(BodyFace.BACK).flatten()
        assertThat(backBounds.minOf { it.x }).isWithin(TOL).of(frontBounds.minOf { it.x })
        assertThat(backBounds.maxOf { it.x }).isWithin(TOL).of(frontBounds.maxOf { it.x })
        assertThat(backBounds.maxOf { it.y }).isWithin(TOL).of(frontBounds.maxOf { it.y })
    }


    @Test
    fun an03_every_keyframe_pose_stays_inside_the_box() {
        assertThat(AnimationClips.ALL).isNotEmpty()
        AnimationClips.ALL.forEach { clip ->
            clip.keyframes.forEachIndexed { index, frame ->
                val polygons = BodySkeleton.outlinePolygons(clip.face, frame.pose) +
                    BodySkeleton.worldPolygons(clip.face, frame.pose).values.flatten()
                assertThat(polygons).isNotEmpty()
                polygons.flatten().forEach { point ->
                    assertWithMessage("${clip.id} keyframe $index at $point")
                        .that(
                            point.x >= 0f && point.x <= MusclePaths.WIDTH &&
                                point.y >= 0f && point.y <= MusclePaths.HEIGHT,
                        )
                        .isTrue()
                }
                // …and the figure still fills a useful part of it, rather than hiding in a corner.
                val ys = polygons.flatten().map { it.y }
                assertWithMessage("${clip.id} keyframe $index is too small")
                    .that(ys.max() - ys.min() + span(polygons))
                    .isGreaterThan(60f)
            }
        }
    }

    @Test
    fun an04_side_face_has_every_segment_and_muscle_coverage() {
        val side = BodySkeleton.SIDE
        assertThat(side.map { it.id }).containsExactlyElementsIn(BodySegmentId.entries)
        // The profile is its own set of bones, not the front one re-labelled.
        assertThat(side.first { it.id == BodySegmentId.TORSO }.parent).isNull()
        side.forEach { segment ->
            assertWithMessage("${segment.id} outline").that(segment.outline.size).isAtLeast(3)
        }

        val groups = MusclePaths.SIDE
        assertWithMessage("groups visible in profile: ${groups.keys}")
            .that(groups.size)
            .isAtLeast(10)
        groups.forEach { (group, polygons) ->
            assertWithMessage("$group").that(polygons).isNotEmpty()
            polygons.forEach { assertWithMessage("$group").that(it.size).isAtLeast(3) }
        }
        // What a profile can and cannot show: the flank and both sides of every limb, but not the
        // adductors, which live on the inside of the far thigh.
        assertThat(groups.keys).containsAtLeast(
            MuscleGroup.CHEST, MuscleGroup.ABS, MuscleGroup.OBLIQUES, MuscleGroup.LATS,
            MuscleGroup.LOWER_BACK, MuscleGroup.TRAPS, MuscleGroup.GLUTES, MuscleGroup.QUADS,
            MuscleGroup.HAMSTRINGS, MuscleGroup.CALVES, MuscleGroup.BICEPS, MuscleGroup.TRICEPS,
        )
        assertThat(groups).doesNotContainKey(MuscleGroup.ADDUCTORS)
        // The double figure of `BodyFigure` is still exactly front + back.
        assertThat(MusclePaths.pathsFor(MuscleGroup.CHEST))
            .isEqualTo(MusclePaths.FRONT.getValue(MuscleGroup.CHEST))
    }

    @Test
    fun an05_root_offset_and_angle_move_the_whole_figure() {
        val standing = BodySkeleton.outlinePolygons(BodyFace.SIDE, BodyPose.STANDING).flatten()

        // A pure offset is a rigid translation: every point moves by exactly the same vector.
        val shifted = BodySkeleton
            .outlinePolygons(BodyFace.SIDE, BodyPose(rootOffsetX = 7f, rootOffsetY = -3f))
            .flatten()
        assertThat(shifted).hasSize(standing.size)
        standing.zip(shifted).forEach { (before, after) ->
            assertThat(after.x).isWithin(TOL).of(before.x + 7f)
            assertThat(after.y).isWithin(TOL).of(before.y - 3f)
        }

        // A +90° root angle lays the figure down about the hip: the pelvis pivot stays put and the
        // upright 100 x 220 figure becomes a wide, flat one.
        val pivot = frames(BodySkeleton.SIDE, BodyPose.STANDING).getValue(BodySegmentId.PELVIS)
            .apply(BodyPoint(0f, 0f))
        assertThat(pivot.x).isWithin(0.01f).of(BodyPose.ROOT_PIVOT_X)
        assertThat(pivot.y).isWithin(0.01f).of(BodyPose.ROOT_PIVOT_Y)
        val lying = frames(BodySkeleton.SIDE, BodyPose(rootAngle = 90f))
            .getValue(BodySegmentId.PELVIS).apply(BodyPoint(0f, 0f))
        assertThat(lying.x).isWithin(0.01f).of(pivot.x)
        assertThat(lying.y).isWithin(0.01f).of(pivot.y)

        val flat = BodySkeleton.outlinePolygons(BodyFace.SIDE, BodyPose(rootAngle = 90f)).flatten()
        val upright = standing
        assertThat(width(flat)).isGreaterThan(width(upright) * 3f)
        assertThat(height(flat)).isLessThan(height(upright) / 3f)
        // …and the head, which was above the hip, is now to its right (+90° is clockwise, prone).
        val head = frames(BodySkeleton.SIDE, BodyPose(rootAngle = 90f)).getValue(BodySegmentId.HEAD)
            .apply(BodyPoint(0f, 0f))
        assertThat(head.x).isGreaterThan(pivot.x + 50f)
        assertThat(abs(head.y - pivot.y)).isLessThan(5f)

        // Root scale shrinks about the same pivot, halving every distance from it.
        val half = BodySkeleton.outlinePolygons(BodyFace.SIDE, BodyPose(rootScale = 0.5f)).flatten()
        standing.zip(half).forEach { (before, after) ->
            val px = BodyPose.ROOT_PIVOT_X
            val py = BodyPose.ROOT_PIVOT_Y
            assertThat(after.x).isWithin(TOL).of(px + (before.x - px) / 2f)
            assertThat(after.y).isWithin(TOL).of(py + (before.y - py) / 2f)
        }
    }

    @Test
    fun an06_squat_bottom_lowers_the_pelvis_and_flexes_knees() {
        val squat = AnimationClips.SQUAT
        assertThat(squat.face).isEqualTo(BodyFace.SIDE)
        val top = squat.keyframes.first().pose
        val bottom = squat.keyframes.last().pose

        // The knee really bends, and the hip flexes the other way (the plan's sign convention).
        assertThat(bottom.angleOf(BodySegmentId.SHANK_R)).isGreaterThan(45f)
        assertThat(bottom.angleOf(BodySegmentId.THIGH_R)).isLessThan(-45f)
        assertThat(abs(top.angleOf(BodySegmentId.SHANK_R))).isLessThan(10f)

        // The pelvis drops while the foot stays on the floor — the point of the root offset.
        val topPelvis = origin(squat.face, top, BodySegmentId.PELVIS)
        val bottomPelvis = origin(squat.face, bottom, BodySegmentId.PELVIS)
        assertThat(bottomPelvis.y).isGreaterThan(topPelvis.y + 10f)
        val topAnkle = origin(squat.face, top, BodySegmentId.FOOT_R)
        val bottomAnkle = origin(squat.face, bottom, BodySegmentId.FOOT_R)
        assertThat(bottomAnkle.x).isWithin(1f).of(topAnkle.x)
        assertThat(bottomAnkle.y).isWithin(1f).of(topAnkle.y)
        // …and the trunk tips forward, so the head ends up ahead of the hip (the subject faces +x).
        assertThat(origin(squat.face, bottom, BodySegmentId.HEAD).x).isGreaterThan(bottomPelvis.x + 10f)
    }

    @Test
    fun an07_plank_is_horizontal() {
        val plank = AnimationClips.PLANK
        assertThat(plank.face).isEqualTo(BodyFace.SIDE)
        plank.keyframes.forEach { frame ->
            // Prone, with the slight head-up slope a forearm plank really has (0.7.1: the elbows
            // and the toes are planted on one floor line, so the shoulders sit an upper arm higher).
            assertThat(frame.pose.rootAngle).isWithin(15f).of(90f)
            // Head, hip and ankle all sit close to one line…
            val ys = listOf(BodySegmentId.HEAD, BodySegmentId.PELVIS, BodySegmentId.FOOT_R)
                .map { origin(plank.face, frame.pose, it).y }
            assertThat(ys.max() - ys.min()).isLessThan(30f)
            // …with the head at the +x end, because +90° is clockwise and the profile faces +x.
            assertThat(origin(plank.face, frame.pose, BodySegmentId.HEAD).x)
                .isGreaterThan(origin(plank.face, frame.pose, BodySegmentId.FOOT_R).x + 40f)
            val polygons = BodySkeleton.outlinePolygons(plank.face, frame.pose)
            assertThat(width(polygons.flatten())).isGreaterThan(height(polygons.flatten()) * 2f)
        }
        // The supine clips lie the other way: head at the −x end (−90°).
        assertThat(AnimationClips.BENCH_PRESS.keyframes.first().pose.rootAngle).isEqualTo(-90f)
    }

    private fun origin(face: BodyFace, pose: BodyPose, id: BodySegmentId): BodyPoint =
        frames(BodySkeleton.segmentsOf(face), pose).getValue(id).apply(BodyPoint(0f, 0f))

    private fun width(points: List<BodyPoint>) = points.maxOf { it.x } - points.minOf { it.x }

    private fun height(points: List<BodyPoint>) = points.maxOf { it.y } - points.minOf { it.y }

    private fun span(polygons: List<MusclePaths.Polygon>): Float = width(polygons.flatten())

    private fun distance(a: BodyPoint, b: BodyPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val TOL = 0.001f
    }
}
