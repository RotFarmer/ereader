package com.rotfarmer.ereader.ink

import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.rotfarmer.ereader.layout.ImageItem
import com.rotfarmer.ereader.layout.Page
import com.rotfarmer.ereader.layout.PageItem
import com.rotfarmer.ereader.layout.PdfSliceItem
import com.rotfarmer.ereader.layout.RuleItem
import com.rotfarmer.ereader.layout.SpaceItem
import com.rotfarmer.ereader.layout.TextItem
import com.rotfarmer.ereader.render.Eink

/** Where an anchor sits on the current page: origin and the length of one unit, in page pixels. */
class Placement(val ox: Float, val oy: Float, val unit: Float, val clip: RectF?)

object Anchoring {
    /** Calls [fn] for every stroke visible on [page], with where to draw it. */
    @Suppress("UNUSED_PARAMETER")
    fun forEachStroke(page: Page, ink: InkStore, content: RectF, viewW: Int, fn: (InkStroke, Placement) -> Unit) {
        for (item in page.items) {
            val colW = item.colRight - item.colLeft
            when (item) {
                is TextItem -> {
                    val list = ink.get(Anchor.BLOCK, item.block.toLong())
                    if (list.isEmpty()) continue
                    val s0 = item.absStart
                    val s1 = item.absEnd
                    for (s in list) {
                        val off = s.anchor.a2
                        if (off < s0 || off >= s1) continue
                        val line = item.layout.getLineForOffset(off - item.pieceStart).coerceIn(item.lineStart, item.lineEnd - 1)
                        fn(s, Placement(item.colLeft, item.lineTop(line), colW, null))
                    }
                }
                is ImageItem -> for (s in ink.get(Anchor.BLOCK, item.block.toLong())) {
                    fn(s, Placement(item.rect.left, item.rect.top, item.rect.width(), null))
                }
                is RuleItem -> for (s in ink.get(Anchor.BLOCK, item.block.toLong())) {
                    fn(s, Placement(item.colLeft, item.rect.top, colW, null))
                }
                is SpaceItem -> for (s in ink.get(Anchor.SPACE, item.space.id)) {
                    if (s.anchor.a2 == item.part) fn(s, Placement(item.rect.left, item.rect.top, item.rect.width(), null))
                }
                is PdfSliceItem -> {
                    val clip = if (item.isWhole) null else RectF(item.colLeft, item.rect.top, item.colRight, item.rect.bottom)
                    for (s in ink.get(Anchor.PDF, item.page.toLong())) {
                        fn(s, Placement(item.pageRect.left, item.pageRect.top, item.pageRect.width(), clip))
                    }
                }
            }
        }
    }

    /** The page item nearest to a point: same column first, then the nearest vertically. */
    fun itemAt(page: Page, x: Float, y: Float): PageItem? {
        var best: PageItem? = null
        var bestD = Float.MAX_VALUE
        for (it in page.items) {
            val dx = when {
                x < it.colLeft -> it.colLeft - x
                x > it.colRight -> x - it.colRight
                else -> 0f
            }
            val dy = when {
                y < it.rect.top -> it.rect.top - y
                y > it.rect.bottom -> y - it.rect.bottom
                else -> 0f
            }
            // Strongly prefer the column the point is in.
            val d = dy + dx * 10f
            if (d < bestD) {
                bestD = d; best = it
            }
        }
        return best
    }

    /** What a new stroke centred at (x, y) should be attached to. */
    @Suppress("UNUSED_PARAMETER")
    fun anchorAt(page: Page, x: Float, y: Float, content: RectF, viewW: Int): Pair<Anchor, Placement>? {
        return when (val item = itemAt(page, x, y)) {
            is TextItem -> {
                val line = item.lineAt(y)
                val off = item.pieceStart + item.layout.getLineStart(line)
                Anchor(Anchor.BLOCK, item.block.toLong(), off) to Placement(item.colLeft, item.lineTop(line), item.colRight - item.colLeft, null)
            }
            is ImageItem -> Anchor(Anchor.BLOCK, item.block.toLong(), 0) to Placement(item.rect.left, item.rect.top, item.rect.width(), null)
            is RuleItem -> Anchor(Anchor.BLOCK, item.block.toLong(), 0) to Placement(item.colLeft, item.rect.top, item.colRight - item.colLeft, null)
            is SpaceItem -> Anchor(Anchor.SPACE, item.space.id, item.part) to Placement(item.rect.left, item.rect.top, item.rect.width(), null)
            is PdfSliceItem -> Anchor(Anchor.PDF, item.page.toLong(), 0) to Placement(
                item.pageRect.left, item.pageRect.top, item.pageRect.width(),
                if (item.isWhole) null else RectF(item.colLeft, item.rect.top, item.colRight, item.rect.bottom)
            )
            null -> null
        }
    }
}

object InkColors {
    /** In dark mode dark inks become light (keeping their hue) so they stay readable. */
    fun display(color: Int, dark: Boolean): Int {
        if (!dark) return color
        val hsl = FloatArray(3)
        rgbToHsl(color, hsl)
        hsl[2] = (1f - hsl[2]).coerceIn(0.3f, 0.92f)
        return hslToRgb(hsl, Color.alpha(color))
    }

    private fun rgbToHsl(c: Int, out: FloatArray) {
        val r = Color.red(c) / 255f
        val g = Color.green(c) / 255f
        val b = Color.blue(c) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2
        var h = 0f
        var s = 0f
        if (max != min) {
            val d = max - min
            s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
            h = when (max) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6f
        }
        out[0] = h; out[1] = s; out[2] = l
    }

    private fun hslToRgb(hsl: FloatArray, alpha: Int): Int {
        val (h, s, l) = Triple(hsl[0], hsl[1], hsl[2])
        if (s == 0f) {
            val v = (l * 255).toInt(); return Color.argb(alpha, v, v, v)
        }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
        }
        return Color.argb(alpha, (hue(h + 1f / 3) * 255).toInt(), (hue(h) * 255).toInt(), (hue(h - 1f / 3) * 255).toInt())
    }
}

/** Draws strokes in their pen style. */
class InkPainter {
    var dark = false
    var sensitivity = 0.75f
        set(v) {
            field = v
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val grain = BitmapShader(Eink.grainTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val grainMatrix = Matrix()

    fun draw(c: Canvas, s: InkStroke, pl: Placement) {
        val shape = s.shape ?: StrokeGeometry.build(s.pts, s.count, s.tool, s.width, sensitivity).also { s.shape = it }
        c.save()
        pl.clip?.let { c.clipRect(it) }
        c.translate(pl.ox, pl.oy)
        c.scale(pl.unit, pl.unit)
        c.drawPath(shape.path, paint(s.tool, s.color, s.width, shape, pl.unit))
        c.restore()
    }

    /** Draws a stroke that is still being written, in screen pixels. */
    fun drawLive(c: Canvas, pts: FloatArray, count: Int, tool: Int, color: Int, width: Float) {
        if (count == 0) return
        val shape = StrokeGeometry.build(pts, count, tool, width, sensitivity)
        c.drawPath(shape.path, paint(tool, color, width, shape, 1f))
    }

    private fun paint(tool: Int, color: Int, width: Float, shape: StrokeShape, unit: Float): Paint {
        val col = InkColors.display(color, dark)
        return when (tool) {
            Tools.HIGHLIGHTER -> line.apply {
                shader = null
                strokeWidth = width
                strokeCap = Paint.Cap.BUTT
                if (dark) {
                    this.color = Color.rgb((Color.red(color) * 0.5f).toInt(), (Color.green(color) * 0.5f).toInt(), (Color.blue(color) * 0.5f).toInt())
                    blendMode = BlendMode.SCREEN
                } else {
                    this.color = (color and 0x00FFFFFF) or 0xB4000000.toInt()
                    blendMode = BlendMode.MULTIPLY
                }
            }
            Tools.MARKER -> line.apply {
                shader = null
                blendMode = null
                strokeWidth = width
                strokeCap = Paint.Cap.ROUND
                this.color = (col and 0x00FFFFFF) or 0xF0000000.toInt()
            }
            Tools.PENCIL -> fill.apply {
                blendMode = null
                val a = ((0.5f + 0.45f * shape.avgPressure) * 255).toInt().coerceIn(60, 255)
                this.color = (col and 0x00FFFFFF) or (a shl 24)
                grainMatrix.setScale(1f / unit, 1f / unit)
                grain.setLocalMatrix(grainMatrix)
                shader = grain
            }
            Tools.SOFT_BRUSH -> fill.apply {
                blendMode = null
                shader = null
                val a = (Color.alpha(col) * 0.4f).toInt().coerceAtMost(110)
                this.color = (col and 0x00FFFFFF) or (a shl 24)
            }
            else -> fill.apply {
                blendMode = null
                shader = null
                this.color = col
            }
        }
    }
}
