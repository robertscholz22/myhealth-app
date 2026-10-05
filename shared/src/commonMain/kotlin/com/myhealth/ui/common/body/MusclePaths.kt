package com.myhealth.ui.common.body

import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.model.BodyFace
import com.myhealth.domain.model.BodyPose
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.StrengthWorkout

/**
 * The body figure's geometry (PLAN §3.12.2, P14.7; rebuilt in P15.1): front and back silhouettes
 * plus, per [MuscleGroup], one or more closed polygons in a normalised **100 × 220** box.
 * Deliberately Compose-free (a plain `Float` point, not `androidx.compose.ui.geometry.Offset`) so it
 * is a plain JVM unit test (`MusclePathsTest`) rather than an instrumented one — the actual drawing
 * lives in [BodyFigure], which scales these polygons to the composable's size.
 *
 * Since P15.1 the shapes are not written down here: they are *derived* from [BodySkeleton] — a
 * jointed segment model — at [BodyPose.STANDING], so the same rounded, human-looking figure can
 * later be posed and animated (P15.2) without a second set of drawings. This object stays the
 * stable façade every call site and `bf01`…`bf05` already speak to.
 */
object MusclePaths {

    /** The box every polygon is normalised to. */
    const val WIDTH: Float = 100f
    const val HEIGHT: Float = 220f

    /** One closed shape: a plain vertex list — the canvas connects the last point back to the first. */
    typealias Polygon = List<BodyPoint>

    /** Body outline pieces (head, neck, torso, pelvis, arms, hands, legs, feet) — not muscle groups. */
    val FRONT_OUTLINE: List<Polygon> = BodySkeleton.outlinePolygons(BodyFace.FRONT)

    val BACK_OUTLINE: List<Polygon> = BodySkeleton.outlinePolygons(BodyFace.BACK)

    /** The P18.1 profile silhouette, facing +x — the standing pose of `BodySkeleton.SIDE`. */
    val SIDE_OUTLINE: List<Polygon> = BodySkeleton.outlinePolygons(BodyFace.SIDE)

    /** The nine front-visible groups (§2.1: `side == FRONT` or `BOTH`). */
    val FRONT: Map<MuscleGroup, List<Polygon>> = BodySkeleton.worldPolygons(BodyFace.FRONT)

    /** The nine back-visible groups. */
    val BACK: Map<MuscleGroup, List<Polygon>> = BodySkeleton.worldPolygons(BodyFace.BACK)

    /**
     * The fifteen groups visible edge-on (everything but `ADDUCTORS`) — P18.1. Deliberately *not*
     * part of [pathsFor]: the front/back pair is what the muscle map and the tap filter speak, and
     * `bf01`/`bf03` pin that contract. The profile exists for the exercise animations.
     */
    val SIDE: Map<MuscleGroup, List<Polygon>> = BodySkeleton.worldPolygons(BodyFace.SIDE)

    /** Every polygon of [group], front and back combined — `bf01`. */
    fun pathsFor(group: MuscleGroup): List<Polygon> = FRONT[group].orEmpty() + BACK[group].orEmpty()

    /**
     * The group whose shape contains `(x, y)` in [groups] (a [FRONT] or [BACK] map), by ray casting
     * against the polygons themselves (`bm03`) — P14.7 compared bounding boxes, which on the rounded
     * P15.1 figure would claim large empty areas beside a limb. `null` outside every group.
     */
    fun groupAt(groups: Map<MuscleGroup, List<Polygon>>, x: Float, y: Float): MuscleGroup? =
        groups.entries.firstOrNull { (_, polygons) ->
            polygons.any { pointInPolygon(it, x, y) }
        }?.key
}

/** A vertex of a [MusclePaths.Polygon], normalised to the 100 × 220 box. */
data class BodyPoint(val x: Float, val y: Float)

/**
 * The intensity [BodyFigure] fills a group with, clamped to `0f..1f` (§3.12.2): `1.0` a primary
 * mover, `0.35` a secondary one — the same 35 % alpha the spec gives "secondary" everywhere else
 * (`bf04`).
 */
fun highlightFor(exercise: Exercise): Map<MuscleGroup, Float> =
    (exercise.primary.associateWith { 1.0f } + exercise.secondary.associateWith { 0.35f })
        .mapValues { it.value.coerceIn(0f, 1f) }

/**
 * The union of every exercise a workout prescribes, one row's [Exercise] resolved off
 * [ExerciseCatalog] at a time — the group's *highest* intensity across the whole workout wins
 * (`bf05`), so an exercise that hits a muscle as a secondary elsewhere does not dim a group another
 * row already hits as its primary. A row naming an id the catalog dropped contributes nothing.
 */
fun highlightFor(workout: StrengthWorkout): Map<MuscleGroup, Float> {
    val result = mutableMapOf<MuscleGroup, Float>()
    workout.exercises.forEach { row ->
        val exercise = ExerciseCatalog.byId(row.exerciseId) ?: return@forEach
        highlightFor(exercise).forEach { (group, value) ->
            result[group] = maxOf(result[group] ?: 0f, value)
        }
    }
    return result.mapValues { it.value.coerceIn(0f, 1f) }
}
