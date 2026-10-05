package com.myhealth.ui.common.body

import com.myhealth.domain.model.BodyFace
import com.myhealth.domain.model.BodyPose
import com.myhealth.domain.model.BodySegmentId
import com.myhealth.domain.model.MuscleGroup
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The body figure's **jointed segment model** (P15.1, extended in P18.1). Where P14.7 drew a fixed
 * list of rectangles, the figure is a little skeleton: sixteen [BodySegment]s hanging off the
 * torso, each carrying a rounded outline and its muscle regions in its *own* local frame, and a
 * [BodyPose] that rotates segments about their joints. Composing the parent chain
 * ([worldPolygons] / [outlinePolygons]) produces exactly what the drawing code has always
 * consumed — closed polygons in the normalised **100 x 220** box of [MusclePaths].
 *
 * Since P18.1 the pose *vocabulary* ([BodySegmentId], [BodyFace], [BodyPose]) lives in
 * `domain/model/Body.kt` so the exercise catalog can author animation clips against it; only the
 * geometry and the forward kinematics are here. [BodyPose]'s four root numbers — rotation, scale
 * and translation about the standing hip joint — are applied *after* the joint chain, which is
 * what lets a plank lie horizontal and a jump leave the floor.
 *
 * Pure Kotlin: no Compose types, no `android.*`. `BodyModelTest` (`bm01`...`bm05`, `an03`...`an07`)
 * is a plain JVM test.
 */

/**
 * One rigid part of the figure.
 *
 * @param pivot the joint's position **in the parent's local frame** (in the 100 x 220 box itself
 *   when [parent] is `null`, i.e. for the root [BodySegmentId.TORSO]).
 * @param outline the part's silhouette **in the segment's own local frame**, pivot at the origin.
 * @param muscles the muscle regions drawn on this part, in the same local frame as [outline].
 */
data class BodySegment(
    val id: BodySegmentId,
    val parent: BodySegmentId?,
    val pivot: BodyPoint,
    val outline: MusclePaths.Polygon,
    val muscles: Map<MuscleGroup, List<MusclePaths.Polygon>> = emptyMap(),
)

/**
 * The three skeletons. `FRONT` and `BACK` are mirror images sharing one set of bones (see
 * `BodyGeometry`); `SIDE` is the P18.1 profile, its own bones and its own regions (`BodyProfile`).
 */
object BodySkeleton {

    val FRONT: List<BodySegment> = BodyGeometry.segments(BodyMuscles.FRONT)

    val BACK: List<BodySegment> = BodyGeometry.mirrored(BodyGeometry.segments(BodyMuscles.BACK))

    /** The profile silhouette, facing +x — every segment id, the groups visible edge-on. */
    val SIDE: List<BodySegment> = BodyProfile.segments()

    fun segmentsOf(face: BodyFace): List<BodySegment> = when (face) {
        BodyFace.FRONT -> FRONT
        BodyFace.BACK -> BACK
        BodyFace.SIDE -> SIDE
    }
}

/**
 * Every muscle region of [face] in world space (the 100 × 220 box) at [pose], merged across the
 * segments that carry the group — e.g. `FOREARMS` collects one polygon from each forearm.
 */
fun BodySkeleton.worldPolygons(
    face: BodyFace,
    pose: BodyPose = BodyPose.STANDING,
): Map<MuscleGroup, List<MusclePaths.Polygon>> {
    val segments = segmentsOf(face)
    val frames = frames(segments, pose)
    val result = LinkedHashMap<MuscleGroup, MutableList<MusclePaths.Polygon>>()
    segments.forEach { segment ->
        val frame = frames.getValue(segment.id)
        segment.muscles.forEach { (group, polygons) ->
            val target = result.getOrPut(group) { mutableListOf() }
            polygons.forEach { target += frame.apply(it) }
        }
    }
    return result
}

/** Every silhouette piece of [face] in world space at [pose] — head, limbs, torso, hands, feet. */
fun BodySkeleton.outlinePolygons(
    face: BodyFace,
    pose: BodyPose = BodyPose.STANDING,
): List<MusclePaths.Polygon> {
    val segments = segmentsOf(face)
    val frames = frames(segments, pose)
    return segments.map { frames.getValue(it.id).apply(it.outline) }
}

/** `true` when ([x], [y]) is inside [polygon] — the ray-casting test `groupAt` hit-tests with. */
fun pointInPolygon(polygon: MusclePaths.Polygon, x: Float, y: Float): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        if ((a.y > y) != (b.y > y)) {
            val cut = (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x
            if (x < cut) inside = !inside
        }
        j = i
    }
    return inside
}

/**
 * A rigid transform (rotation + translation) from a segment's local frame to the box. Kept as the
 * four numbers rather than a matrix class so the model owes nothing to Compose or `android.graphics`.
 */
data class BodyFrame(val cos: Float, val sin: Float, val tx: Float, val ty: Float) {

    fun apply(point: BodyPoint): BodyPoint =
        BodyPoint(cos * point.x - sin * point.y + tx, sin * point.x + cos * point.y + ty)

    fun apply(polygon: MusclePaths.Polygon): MusclePaths.Polygon = polygon.map { apply(it) }

    /** `this ∘ child`: [child]'s frame expressed in this frame's parent. */
    fun then(child: BodyFrame): BodyFrame = BodyFrame(
        cos = cos * child.cos - sin * child.sin,
        sin = sin * child.cos + cos * child.sin,
        tx = cos * child.tx - sin * child.ty + tx,
        ty = sin * child.tx + cos * child.ty + ty,
    )

    companion object {
        fun of(pivot: BodyPoint, degrees: Float): BodyFrame {
            val rad = degrees * DEG_TO_RAD
            return BodyFrame(cos(rad), sin(rad), pivot.x, pivot.y)
        }
    }
}

/**
 * Resolves every segment's local-to-box transform by walking the parent chain once, with memoing.
 * The chain hangs off [rootFrame], which is where a pose's root scale, rotation and translation
 * enter — so they move the figure as one rigid (uniformly scaled) body, whatever the joints do.
 */
fun frames(segments: List<BodySegment>, pose: BodyPose): Map<BodySegmentId, BodyFrame> {
    val byId = segments.associateBy { it.id }
    val root = rootFrame(pose)
    val resolved = HashMap<BodySegmentId, BodyFrame>(segments.size)
    fun resolve(id: BodySegmentId): BodyFrame = resolved.getOrPut(id) {
        val segment = byId.getValue(id)
        val local = BodyFrame.of(segment.pivot, pose.angleOf(id))
        val parent = segment.parent?.let { resolve(it) } ?: root
        parent.then(local)
    }
    segments.forEach { resolve(it.id) }
    return resolved
}

/**
 * The pose's whole-figure transform: scale by `rootScale` and rotate by `rootAngle` about the
 * standing hip joint ([BodyPose.ROOT_PIVOT_X] / `_Y`), then translate by the root offset. A
 * uniform scale commutes with the joint rotations composed under it, so [BodyFrame.then] stays a
 * plain four-number composition.
 */
internal fun rootFrame(pose: BodyPose): BodyFrame {
    val rad = pose.rootAngle * DEG_TO_RAD
    val c = cos(rad) * pose.rootScale
    val s = sin(rad) * pose.rootScale
    val px = BodyPose.ROOT_PIVOT_X
    val py = BodyPose.ROOT_PIVOT_Y
    return BodyFrame(
        cos = c,
        sin = s,
        tx = px + pose.rootOffsetX - (c * px - s * py),
        ty = py + pose.rootOffsetY - (s * px + c * py),
    )
}

private const val DEG_TO_RAD = (PI / 180.0).toFloat()
