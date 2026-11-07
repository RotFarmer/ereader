package com.rotfarmer.ereader.ink

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** Turns raw pen samples into a smooth outline whose width follows pen pressure. */
object StrokeGeometry {
    private const val NIB_ANGLE = 0.70f // radians, about 40°

    fun pressureFactor(tool: Int, p: Float, sensitivity: Float): Float {
        val pp = p.coerceIn(0f, 1f)
        val curve = when (tool) {
            Tools.PEN -> 0.55f + 0.75f * pp
            Tools.FOUNTAIN -> 0.16f + 1.6f * pp.pow(1.3f)
            Tools.CALLIGRAPHY -> 0.55f + 0.75f * pp
            Tools.PENCIL -> 0.6f + 0.65f * pp
            Tools.MARKER -> 0.85f + 0.3f * pp
            else -> 1f
        }
        return 1f + (curve - 1f) * sensitivity.coerceIn(0f, 1f)
    }

    fun build(pts: FloatArray, count: Int, tool: Int, width: Float, sensitivity: Float): StrokeShape {
        val n = count
        val path = Path()
        if (n == 0) return StrokeShape(path, true, 0.5f)

        // Lightly smoothed positions and pressures.
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val ps = FloatArray(n)
        var pSum = 0f
        for (i in 0 until n) {
            val j = i * 3
            if (i == 0 || i == n - 1) {
                xs[i] = pts[j]; ys[i] = pts[j + 1]
            } else {
                xs[i] = (pts[j - 3] + 2 * pts[j] + pts[j + 3]) / 4f
                ys[i] = (pts[j - 2] + 2 * pts[j + 1] + pts[j + 4]) / 4f
            }
            val raw = pts[j + 2]
            ps[i] = if (i == 0) raw else ps[i - 1] * 0.4f + raw * 0.6f
            pSum += ps[i]
        }
        val avgP = pSum / n

        if (tool == Tools.MARKER || tool == Tools.HIGHLIGHTER) {
            path.moveTo(xs[0], ys[0])
            if (n == 1) path.lineTo(xs[0] + width * 0.05f, ys[0]) else smooth(path, xs, ys, n)
            return StrokeShape(path, false, avgP)
        }

        val ws = FloatArray(n)
        var maxW = 0f
        for (i in 0 until n) {
            var w = width * pressureFactor(tool, ps[i], sensitivity)
            if (tool == Tools.CALLIGRAPHY) {
                val a = if (n < 2) 0f else {
                    val i0 = (i - 1).coerceAtLeast(0)
                    val i1 = (i + 1).coerceAtMost(n - 1)
                    atan2(ys[i1] - ys[i0], xs[i1] - xs[i0])
                }
                w *= 0.16f + 0.84f * abs(sin(a - NIB_ANGLE))
            }
            ws[i] = w.coerceAtLeast(width * 0.06f)
            if (ws[i] > maxW) maxW = ws[i]
        }

        var length = 0f
        for (i in 1 until n) length += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if (n == 1 || length < maxW * 0.25f) {
            path.addCircle(xs[0], ys[0], maxW / 2f, Path.Direction.CW)
            return StrokeShape(path, true, avgP)
        }

        val nx = FloatArray(n)
        val ny = FloatArray(n)
        var lx = 0f
        var ly = -1f
        for (i in 0 until n) {
            val i0 = (i - 1).coerceAtLeast(0)
            val i1 = (i + 1).coerceAtMost(n - 1)
            val dx = xs[i1] - xs[i0]
            val dy = ys[i1] - ys[i0]
            val len = hypot(dx, dy)
            if (len > 1e-6f) {
                lx = -dy / len; ly = dx / len
            }
            nx[i] = lx; ny[i] = ly
        }
        val leftX = FloatArray(n) { xs[it] + nx[it] * ws[it] / 2 }
        val leftY = FloatArray(n) { ys[it] + ny[it] * ws[it] / 2 }
        val rightX = FloatArray(n) { xs[n - 1 - it] - nx[n - 1 - it] * ws[n - 1 - it] / 2 }
        val rightY = FloatArray(n) { ys[n - 1 - it] - ny[n - 1 - it] * ws[n - 1 - it] / 2 }

        path.moveTo(leftX[0], leftY[0])
        smooth(path, leftX, leftY, n)
        // Round tip at the end.
        val re = ws[n - 1] / 2
        path.arcTo(
            RectF(xs[n - 1] - re, ys[n - 1] - re, xs[n - 1] + re, ys[n - 1] + re),
            Math.toDegrees(atan2(ny[n - 1], nx[n - 1]).toDouble()).toFloat(), -180f, false
        )
        smooth(path, rightX, rightY, n)
        // Round tip at the start.
        val rs = ws[0] / 2
        path.arcTo(
            RectF(xs[0] - rs, ys[0] - rs, xs[0] + rs, ys[0] + rs),
            Math.toDegrees(atan2(ny[0], nx[0]).toDouble()).toFloat() + 180f, -180f, false
        )
        path.close()
        path.fillType = Path.FillType.WINDING
        return StrokeShape(path, true, avgP)
    }

    /** Quadratic curves through the midpoints, starting from the current point at index 0. */
    private fun smooth(path: Path, xs: FloatArray, ys: FloatArray, n: Int) {
        if (n == 2) {
            path.lineTo(xs[1], ys[1]); return
        }
        if (n < 2) return
        path.lineTo(xs[0], ys[0])
        for (i in 1 until n - 1) {
            path.quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2f, (ys[i] + ys[i + 1]) / 2f)
        }
        path.lineTo(xs[n - 1], ys[n - 1])
    }

    /** Shortest distance from (px, py) to the stroke's centre line, in the stroke's units. */
    fun distanceTo(s: InkStroke, px: Float, py: Float): Float {
        val p = s.pts
        var best = Float.MAX_VALUE
        if (p.size < 6) return if (p.size >= 2) hypot(p[0] - px, p[1] - py) else best
        var i = 0
        while (i + 5 < p.size) {
            val d = segDist(px, py, p[i], p[i + 1], p[i + 3], p[i + 4])
            if (d < best) best = d
            i += 3
        }
        return best
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-9f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
