package com.myhealth.ui.common.body

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The little bit of geometry the body figure is drawn from (P15.1): every shape in [BodySkeleton]
 * is a *closed* polygon, and these helpers are what turn a handful of hand-placed control points
 * into one smooth enough to read as a body part rather than a box.
 *
 * Pure Kotlin — no Compose types — so the whole body model stays unit-testable on the JVM
 * (`BodyModelTest`), exactly as [MusclePaths] has been since P14.7.
 */
internal object BodyShapes {

    /** Points emitted per control-point span by [smoothClosed]; 5 keeps a 10-point outline at 50. */
    const val DEFAULT_STEPS: Int = 5

    /**
     * A closed Catmull-Rom spline through [control], sampled [steps] times per span. The result is
     * a plain polygon (the drawing code only knows how to fill polygons), but with enough vertices
     * — ≥ 12 per curve for every shape in the skeleton — that the fill reads as a smooth, organic
     * outline. The curve passes *through* every control point, so hand-tuning a shape means moving
     * a point and getting exactly that.
     */
    fun smoothClosed(control: List<BodyPoint>, steps: Int = DEFAULT_STEPS): MusclePaths.Polygon {
        if (control.size < 3 || steps < 1) return control
        val n = control.size
        val out = ArrayList<BodyPoint>(n * steps)
        for (i in 0 until n) {
            val p0 = control[(i - 1 + n) % n]
            val p1 = control[i]
            val p2 = control[(i + 1) % n]
            val p3 = control[(i + 2) % n]
            for (s in 0 until steps) {
                out += catmullRom(p0, p1, p2, p3, s.toFloat() / steps)
            }
        }
        return out
    }

    /** An ellipse centred on ([cx], [cy]) — the head and the hands. */
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, steps: Int = 24): MusclePaths.Polygon =
        (0 until steps).map { i ->
            val a = TWO_PI * i / steps
            BodyPoint(cx + rx * cos(a), cy + ry * sin(a))
        }

    /**
     * A rounded rectangle ("capsule") whose top-centre is the origin: it spans `x = ±width/2` and
     * `y = 0..height`, with [radius] corners drawn as quarter arcs of [steps] segments each. Used
     * for the blocky-by-nature regions (the rectus abdominis' six blocks) where a spline would only
     * add wobble.
     */
    fun roundedCapsule(width: Float, height: Float, radius: Float, steps: Int = 5): MusclePaths.Polygon {
        val r = radius.coerceAtMost(minOf(width, height) / 2f)
        val left = -width / 2f + r
        val right = width / 2f - r
        val top = r
        val bottom = height - r
        val out = ArrayList<BodyPoint>(steps * 4 + 4)
        arc(out, left, top, r, 180f, 270f, steps)
        arc(out, right, top, r, 270f, 360f, steps)
        arc(out, right, bottom, r, 0f, 90f, steps)
        arc(out, left, bottom, r, 90f, 180f, steps)
        return out
    }

    /** [roundedCapsule] positioned by its bounds instead of by a pivot — the abs blocks. */
    fun roundedBlock(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float): MusclePaths.Polygon {
        val cx = (x0 + x1) / 2f
        return roundedCapsule(x1 - x0, y1 - y0, radius).map { BodyPoint(it.x + cx, it.y + y0) }
    }

    /** The same shape on the other side of its local frame — the body is built left, then mirrored. */
    fun mirrorX(polygon: MusclePaths.Polygon): MusclePaths.Polygon =
        polygon.asReversed().map { BodyPoint(-it.x, it.y) }

    private fun arc(out: MutableList<BodyPoint>, cx: Float, cy: Float, r: Float, from: Float, to: Float, steps: Int) {
        for (i in 0..steps) {
            val a = ((from + (to - from) * i / steps) * DEG_TO_RAD)
            out += BodyPoint(cx + r * cos(a), cy + r * sin(a))
        }
    }

    private fun catmullRom(p0: BodyPoint, p1: BodyPoint, p2: BodyPoint, p3: BodyPoint, t: Float): BodyPoint =
        BodyPoint(
            x = spline(p0.x, p1.x, p2.x, p3.x, t),
            y = spline(p0.y, p1.y, p2.y, p3.y, t),
        )

    private fun spline(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * ((2f * b) + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t2 + (-a + 3f * b - 3f * c + d) * t3)
    }

    private const val TWO_PI = (2.0 * PI).toFloat()
    private const val DEG_TO_RAD = (PI / 180.0).toFloat()
}
