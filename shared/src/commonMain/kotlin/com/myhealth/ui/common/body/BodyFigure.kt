package com.myhealth.ui.common.body

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.BodyFace
import com.myhealth.domain.model.BodyPose
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.ui.theme.MyHealthTheme

/** The two faces the double figure draws by default. */
private val FIGURE_FACES = listOf(BodyFace.FRONT, BodyFace.BACK)

/** The silhouette's outline stroke. */
private val SILHOUETTE_STROKE = 1.dp

/** The hairline between two neighbouring muscle regions. */
private val REGION_STROKE = 0.6.dp

/** How much darker than the silhouette the region hairline is drawn. */
private const val REGION_STROKE_ALPHA = 0.45f

/**
 * The body figure (PLAN §3.12.2, P14.7; redrawn in P15.1): a front silhouette and a back silhouette
 * side by side, each muscle group filled by [highlight]'s intensity for that group — `1.0` reads as
 * `colorScheme.primary`, `0.0` as `colorScheme.surfaceVariant`, and everything between as a linear
 * blend of the two (so `0.35`, the spec's "secondary" value, reads the same as `primary` at 35 %
 * alpha over the card background). Pure `Canvas`, no images, no new dependency.
 *
 * The geometry comes from [BodySkeleton]: rounded, jointed body parts whose world polygons are
 * composed for [pose] and then *unioned* into one silhouette, so the joints do not show as seams.
 * Compose's canvas paints are anti-aliased by default, so the many-vertex splines of `BodyShapes`
 * fill as smooth curves. [pose] is the seam a later exercise animation (P15.2) draws through —
 * every caller today passes [BodyPose.STANDING].
 *
 * The same composable backs the exercise/workout figures (primary/secondary intensities) and the
 * Load screen's muscle heat map (P14.8, a continuous `load / ref` clamped to `0..1`) — [highlight]
 * does not care which produced it.
 *
 * [onFrontTap]/[onBackTap] turn the figure into the Exercises screen's muscle filter (§4.2): a tap
 * is hit-tested against each group's polygons by ray casting ([MusclePaths.groupAt]).
 *
 * Each silhouette gets **half** the width (a weighted [Box]) and is then sized by its 100 : 220
 * aspect ratio inside that half. Without the weight, a caller that constrains the width but not the
 * height — the Load screen's heat map — let the front figure take the whole row and pushed the back
 * one off the edge.
 *
 * [faces] picks which silhouette(s) are drawn, in order — the default front+back pair for the
 * muscle map, or a single [BodyFace.SIDE] (or `FRONT`/`BACK` alone) for the P18.2 exercise
 * animations, which only ever show the one face a clip was authored in.
 */
@Composable
fun BodyFigure(
    highlight: Map<MuscleGroup, Float>,
    modifier: Modifier = Modifier,
    pose: BodyPose = BodyPose.STANDING,
    faces: List<BodyFace> = FIGURE_FACES,
    onFrontTap: ((MuscleGroup) -> Unit)? = null,
    onBackTap: ((MuscleGroup) -> Unit)? = null,
    /** The part of the 100 × 220 box to show (POLISH-21): the animations fit each clip to its content. */
    viewport: BodyViewport = BodyViewport.FULL,
) {
    Row(modifier = modifier) {
        faces.forEach { face ->
            val standing = pose == BodyPose.STANDING
            val groups = remember(face, pose) {
                if (standing) faceGroups(face) else BodySkeleton.worldPolygons(face, pose)
            }
            val outline = remember(face, pose) {
                if (standing) faceOutline(face) else BodySkeleton.outlinePolygons(face, pose)
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                BodyView(
                    groups = groups,
                    outline = outline,
                    highlight = highlight,
                    onTap = when (face) {
                        BodyFace.FRONT -> onFrontTap
                        BodyFace.BACK -> onBackTap
                        BodyFace.SIDE -> null
                    },
                    viewport = viewport,
                    modifier = Modifier.aspectRatio(viewport.width / viewport.height),
                )
            }
        }
    }
}

private fun faceGroups(face: BodyFace) = when (face) {
    BodyFace.FRONT -> MusclePaths.FRONT
    BodyFace.BACK -> MusclePaths.BACK
    BodyFace.SIDE -> MusclePaths.SIDE
}

private fun faceOutline(face: BodyFace) = when (face) {
    BodyFace.FRONT -> MusclePaths.FRONT_OUTLINE
    BodyFace.BACK -> MusclePaths.BACK_OUTLINE
    BodyFace.SIDE -> MusclePaths.SIDE_OUTLINE
}

/**
 * A window onto the normalised 100 × 220 body box, in box units. [FULL] shows the whole box (the
 * muscle maps); the exercise animations pass the bounds of their clip so a horizontal push-up or a
 * wide lateral raise fills the view instead of shrinking into a portrait frame (POLISH-21).
 */
data class BodyViewport(val left: Float, val top: Float, val width: Float, val height: Float) {
    companion object {
        val FULL: BodyViewport = BodyViewport(0f, 0f, MusclePaths.WIDTH, MusclePaths.HEIGHT)
    }
}

@Composable
private fun BodyView(
    groups: Map<MuscleGroup, List<MusclePaths.Polygon>>,
    outline: List<MusclePaths.Polygon>,
    highlight: Map<MuscleGroup, Float>,
    onTap: ((MuscleGroup) -> Unit)?,
    modifier: Modifier = Modifier,
    viewport: BodyViewport = BodyViewport.FULL,
) {
    val unused = MaterialTheme.colorScheme.surfaceVariant
    val filled = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val silhouette = remember(outline) { union(outline) }

    val tapModifier = if (onTap == null) {
        Modifier
    } else {
        Modifier.pointerInput(groups) {
            detectTapGestures { offset ->
                val x = viewport.left + offset.x / size.width * viewport.width
                val y = viewport.top + offset.y / size.height * viewport.height
                MusclePaths.groupAt(groups, x, y)?.let(onTap)
            }
        }
    }

    Canvas(modifier = modifier.then(tapModifier)) {
        // Uniform scale so the viewport fills the canvas (its aspect ratio is the canvas's), then
        // shift so the viewport's top-left lands at the origin.
        val scale = minOf(size.width / viewport.width, size.height / viewport.height)
        val toScreen = Matrix().apply {
            scale(scale, scale)
            translate(-viewport.left, -viewport.top)
        }

        // One merged outline: only the body's own edge is stroked, never the joints inside it.
        val body = Path().apply {
            addPath(silhouette)
            transform(toScreen)
        }
        drawPath(body, color = unused)
        drawPath(body, color = outlineColor, style = Stroke(width = SILHOUETTE_STROKE.toPx()))

        val regionStroke = Stroke(width = REGION_STROKE.toPx())
        val regionOutline = outlineColor.copy(alpha = REGION_STROKE_ALPHA)
        groups.forEach { (group, polygons) ->
            val intensity = (highlight[group] ?: 0f).coerceIn(0f, 1f)
            val color = colorFor(intensity, unused, filled)
            polygons.forEach { polygon ->
                val path = polygon.toPath().apply { transform(toScreen) }
                drawPath(path, color = color)
                drawPath(path, color = regionOutline, style = regionStroke)
            }
        }
    }
}

/** One closed polygon as a [Path], in the normalised 100 × 220 box. */
private fun MusclePaths.Polygon.toPath(): Path = Path().apply {
    forEachIndexed { index, point ->
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}

/**
 * The body parts merged into a single closed shape ([PathOperation.Union]), computed once per
 * pose and reused across draws — stroking the merged path outlines the body, not every segment.
 */
private fun union(polygons: List<MusclePaths.Polygon>): Path =
    polygons.fold(Path()) { merged, polygon ->
        Path().apply { op(merged, polygon.toPath(), PathOperation.Union) }
    }

/** `intensity` linearly blended from [unused] (0) to [filled] (1) — §3.12.2's fill rule. */
private fun colorFor(intensity: Float, unused: Color, filled: Color): Color = lerp(unused, filled, intensity)

@Preview(showBackground = true)
@Composable
private fun BodyFigurePreview() {
    MyHealthTheme(dynamicColor = false) {
        BodyFigure(
            highlight = mapOf(
                MuscleGroup.CHEST to 1.0f,
                MuscleGroup.TRICEPS to 0.35f,
                MuscleGroup.SHOULDERS_FRONT to 0.35f,
                MuscleGroup.QUADS to 0.6f,
            ),
        )
    }
}
