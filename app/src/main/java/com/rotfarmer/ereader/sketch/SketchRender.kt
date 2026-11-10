package com.rotfarmer.ereader.sketch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.rotfarmer.ereader.ink.InkPainter
import com.rotfarmer.ereader.ink.InkStroke
import com.rotfarmer.ereader.ink.Placement
import com.rotfarmer.ereader.render.ReaderColors
import java.io.OutputStream

/** Draws sketchbook pages: on screen, as library pictures and in exports. */
object SketchRender {
    fun paperColor(dark: Boolean) = ReaderColors(dark).paper
    fun deskColor(dark: Boolean) = if (dark) 0xFF0C0C0C.toInt() else 0xFFE6E2D8.toInt()
    private fun ruleColor(dark: Boolean) = if (dark) 0xFF3A3A38.toInt() else 0xFFD3CEC2.toInt()

    /**
     * Paper and ink for one page, [w] pixels wide, with the page's top-left corner at the canvas
     * origin. [lineWidth] is the rule thickness in pixels.
     */
    fun drawPage(
        c: Canvas, w: Float, paper: Int, strokes: List<InkStroke>, dark: Boolean, sensitivity: Float, painter: InkPainter, lineWidth: Float,
        items: List<SketchItem> = emptyList(), bitmap: (SketchItem) -> Bitmap? = { null },
    ) {
        val h = w * SketchFile.ASPECT
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = paperColor(dark)
        c.drawRect(0f, 0f, w, h, p)
        drawRules(c, w, h, paper, dark, lineWidth)
        c.save()
        c.clipRect(0f, 0f, w, h)
        drawItems(c, w, items, bitmap, dark)
        painter.dark = dark
        painter.sensitivity = sensitivity
        val pl = Placement(0f, 0f, w, null)
        for (s in strokes) painter.draw(c, s, pl)
        c.restore()
    }

    private val darkDocument = ReaderColors(true).pdfFilter

    /** Placed pictures and document pages, under the ink. Document pages are inverted in dark mode, like PDFs in the reader. */
    fun drawItems(c: Canvas, w: Float, items: List<SketchItem>, bitmap: (SketchItem) -> Bitmap?, dark: Boolean) {
        if (items.isEmpty()) return
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (it in items) {
            val r = RectF(it.x * w, it.y * w, (it.x + it.w) * w, (it.y + it.h) * w)
            val b = bitmap(it)
            if (b == null || b.isRecycled) { SketchAssets.drawPlaceholder(c, r, dark); continue }
            p.colorFilter = if (dark && it.kind == SketchItem.DOCUMENT) darkDocument else null
            c.drawBitmap(b, null, r, p)
        }
    }

    private fun drawRules(c: Canvas, w: Float, h: Float, paper: Int, dark: Boolean, lineWidth: Float) {
        if (paper == SketchStore.BLANK) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ruleColor(dark); strokeWidth = lineWidth; style = Paint.Style.STROKE }
        val step = w / 22f
        when (paper) {
            SketchStore.LINED -> {
                var y = step * 2.5f
                while (y < h - step * 0.5f) { c.drawLine(0f, y, w, y, p); y += step }
            }
            SketchStore.GRID -> {
                var y = step
                while (y < h) { c.drawLine(0f, y, w, y, p); y += step }
                var x = step
                while (x < w) { c.drawLine(x, 0f, x, h, p); x += step }
            }
            SketchStore.DOTS -> {
                p.style = Paint.Style.FILL
                val r = lineWidth * 1.3f
                var y = step
                while (y < h) {
                    var x = step
                    while (x < w) { c.drawCircle(x, y, r, p); x += step }
                    y += step
                }
            }
        }
    }

    /** A picture of one page, [width] pixels wide (library cards, sharing a page). */
    fun bitmap(width: Int, paper: Int, strokes: List<InkStroke>, dark: Boolean, sensitivity: Float, items: List<SketchItem> = emptyList(), store: SketchStore? = null): Bitmap {
        val b = Bitmap.createBitmap(width, (width * SketchFile.ASPECT).toInt(), Bitmap.Config.ARGB_8888)
        val pictures = if (store == null) emptyMap() else items.associate { it.id to SketchAssets.render(store, it, (it.w * width).toInt()) }
        try {
            drawPage(Canvas(b), width.toFloat(), paper, strokes, dark, sensitivity, InkPainter(), (width / 900f).coerceAtLeast(1f), items) { pictures[it.id] }
        } finally { pictures.values.forEach { it?.recycle() } }
        return b
    }

    /** Every page with drawing on it, as an A4 PDF with sharp (vector) ink, light paper. */
    fun pdf(store: SketchStore, bookId: String, out: OutputStream, sensitivity: Float) {
        val book = store.get(bookId) ?: throw IllegalStateException("Sketchbook missing")
        val pages = store.usedPages(bookId)
        require(pages.isNotEmpty()) { "Nothing drawn in this sketchbook yet" }
        val doc = PdfDocument()
        try {
            val painter = InkPainter()
            pages.forEachIndexed { i, page ->
                val p = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                val items = store.items(bookId, page)
                // About 150 dpi for pictures and document pages.
                val pictures = items.associate { it.id to SketchAssets.render(store, it, (it.w * 1240).toInt()) }
                try {
                    drawPage(p.canvas, 595f, book.paper, store.strokes(bookId, page), false, sensitivity, painter, 0.5f, items) { pictures[it.id] }
                    doc.finishPage(p)
                } finally { pictures.values.forEach { it?.recycle() } }
            }
            doc.writeTo(out)
        } finally { doc.close() }
    }

    /** The page's rectangle for a page [w] wide at ([x], [y]). */
    fun pageRect(x: Float, y: Float, w: Float) = RectF(x, y, x + w, y + w * SketchFile.ASPECT)
}
