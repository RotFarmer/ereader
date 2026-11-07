package com.rotfarmer.ereader.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.rotfarmer.ereader.data.Highlight
import com.rotfarmer.ereader.data.Locator
import com.rotfarmer.ereader.data.WritingSpace
import com.rotfarmer.ereader.layout.ImageItem
import com.rotfarmer.ereader.layout.Page
import com.rotfarmer.ereader.layout.PdfSliceItem
import com.rotfarmer.ereader.layout.RuleItem
import com.rotfarmer.ereader.layout.SpaceItem
import com.rotfarmer.ereader.layout.TextItem
import kotlin.math.max
import kotlin.math.min

class PageRenderer(private val density: Float) {
    var colors = ReaderColors(false)
    var images: ImageCache? = null
    var pdf: PdfCache? = null
    var eink = true
    var highlights: List<Highlight> = emptyList()

    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tmp = Path()
    private val srcRect = Rect()

    fun draw(c: Canvas, page: Page) {
        for (item in page.items) {
            when (item) {
                is TextItem -> drawText(c, item)
                is ImageItem -> drawImage(c, item)
                is RuleItem -> drawRule(c, item)
                is SpaceItem -> drawSpace(c, item.rect, item.space.paper, item.space.kind == WritingSpace.PDF_BLANK)
                is PdfSliceItem -> drawPdf(c, item)
            }
        }
    }

    private fun drawText(c: Canvas, item: TextItem) {
        val layout = item.layout
        val top = item.rect.top - layout.getLineTop(item.lineStart)
        c.save()
        val pad = 24 * density
        c.clipRect(item.rect.left - pad, item.rect.top, item.rect.right + pad, item.rect.bottom)
        c.translate(item.rect.left, top)
        for (h in highlights) {
            val r = clip(item, h.start, h.end) ?: continue
            tmp.reset()
            layout.getSelectionPath(r.first - item.pieceStart, r.second - item.pieceStart, tmp)
            hlPaint.color = colors.highlight(h.color)
            c.drawPath(tmp, hlPaint)
        }
        layout.draw(c)
        c.restore()
    }

    private fun drawImage(c: Canvas, item: ImageItem) {
        val w = item.rect.width().toInt().coerceAtLeast(1)
        val h = item.rect.height().toInt().coerceAtLeast(1)
        val bmp = images?.get(item.path, w, h, eink)
        if (bmp == null) {
            linePaint.color = colors.faint
            linePaint.style = Paint.Style.STROKE
            c.drawRect(item.rect, linePaint)
            return
        }
        bmpPaint.colorFilter = colors.imageFilter
        c.drawBitmap(bmp, null, item.rect, bmpPaint)
    }

    private fun drawRule(c: Canvas, item: RuleItem) {
        linePaint.style = Paint.Style.FILL
        linePaint.color = colors.muted
        val cx = item.rect.centerX()
        val cy = item.rect.centerY()
        val gap = 14 * density
        for (k in -1..1) c.drawCircle(cx + k * gap, cy, 1.6f * density, linePaint)
    }

    private fun drawPdf(c: Canvas, item: PdfSliceItem) {
        val w = item.pageRect.width().toInt().coerceAtLeast(1)
        val bmp = pdf?.get(item.page, w, eink)
        if (bmp == null) {
            linePaint.style = Paint.Style.STROKE
            linePaint.color = colors.faint
            c.drawRect(item.rect, linePaint)
            return
        }
        srcRect.set(0, (item.y0 * bmp.height).toInt(), bmp.width, (item.y1 * bmp.height).toInt())
        bmpPaint.colorFilter = colors.pdfFilter
        c.drawBitmap(bmp, srcRect, item.rect, bmpPaint)
        if (!item.isWhole) {
            // A torn-paper hint where the page was split.
            linePaint.style = Paint.Style.STROKE
            linePaint.color = colors.faint
            linePaint.pathEffect = DashPathEffect(floatArrayOf(6 * density, 5 * density), 0f)
            if (item.y0 > 0f) c.drawLine(item.rect.left, item.rect.top, item.rect.right, item.rect.top, linePaint)
            if (item.y1 < 1f) c.drawLine(item.rect.left, item.rect.bottom, item.rect.right, item.rect.bottom, linePaint)
            linePaint.pathEffect = null
        }
    }

    fun drawSpace(c: Canvas, r: RectF, paper: Int, fullPage: Boolean) {
        linePaint.color = colors.faint
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = density
        val m = 3 * density
        val l = 16 * density
        val x0 = r.left + m
        val x1 = r.right - m
        val y0 = r.top + m
        val y1 = r.bottom - m
        // Corner marks show where the writing area is.
        c.drawLine(x0, y0, x0 + l, y0, linePaint); c.drawLine(x0, y0, x0, y0 + l, linePaint)
        c.drawLine(x1, y0, x1 - l, y0, linePaint); c.drawLine(x1, y0, x1, y0 + l, linePaint)
        c.drawLine(x0, y1, x0 + l, y1, linePaint); c.drawLine(x0, y1, x0, y1 - l, linePaint)
        c.drawLine(x1, y1, x1 - l, y1, linePaint); c.drawLine(x1, y1, x1, y1 - l, linePaint)
        c.save()
        c.clipRect(x0, y0, x1, y1)
        when (paper) {
            WritingSpace.PAPER_LINED -> {
                val step = 30 * density
                var y = y0 + step
                while (y < y1) {
                    c.drawLine(x0, y, x1, y, linePaint); y += step
                }
            }
            WritingSpace.PAPER_GRID -> {
                val step = 22 * density
                var y = y0 + step
                while (y < y1) {
                    c.drawLine(x0, y, x1, y, linePaint); y += step
                }
                var x = x0 + step
                while (x < x1) {
                    c.drawLine(x, y0, x, y1, linePaint); x += step
                }
            }
            WritingSpace.PAPER_DOTS -> {
                linePaint.style = Paint.Style.FILL
                val step = 22 * density
                var y = y0 + step
                while (y < y1) {
                    var x = x0 + step
                    while (x < x1) {
                        c.drawCircle(x, y, 1.1f * density, linePaint); x += step
                    }
                    y += step
                }
                linePaint.style = Paint.Style.STROKE
            }
        }
        c.restore()
    }

    companion object {
        /** The part of [start, end) that falls inside a text item, as absolute block offsets. */
        fun clip(item: TextItem, start: Locator, end: Locator): Pair<Int, Int>? {
            if (item.block < start.block || item.block > end.block) return null
            val s = if (item.block == start.block) start.offset else 0
            val e = if (item.block == end.block) end.offset else Int.MAX_VALUE
            val cs = max(s, item.absStart)
            val ce = min(e, item.absEnd)
            return if (ce > cs) cs to ce else null
        }

        /** Outline of a text range on a page, in page coordinates. */
        fun rangePath(page: Page, start: Locator, end: Locator, out: Path) {
            out.reset()
            val p = Path()
            for (item in page.items) {
                if (item !is TextItem) continue
                val r = clip(item, start, end) ?: continue
                p.reset()
                item.layout.getSelectionPath(r.first - item.pieceStart, r.second - item.pieceStart, p)
                p.offset(item.rect.left, item.rect.top - item.layout.getLineTop(item.lineStart))
                out.addPath(p)
            }
        }
    }
}
